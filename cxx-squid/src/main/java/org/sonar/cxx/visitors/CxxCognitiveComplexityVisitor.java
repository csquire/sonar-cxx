/*
 * Sonar C++ Plugin (Community)
 * Copyright (C) 2010-2017 SonarOpenCommunity
 * http://github.com/SonarOpenCommunity/sonar-cxx
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.cxx.visitors;

import static com.sonar.sslr.api.GenericTokenType.IDENTIFIER;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import com.sonar.sslr.api.AstNode;
import com.sonar.sslr.api.AstNodeType;
import com.sonar.sslr.api.Grammar;
import org.sonar.cxx.api.CxxKeyword;
import org.sonar.cxx.api.CxxPunctuator;
import org.sonar.cxx.parser.CxxGrammarImpl;
import org.sonar.squidbridge.SquidAstVisitor;
import org.sonar.squidbridge.measures.MetricDef;

import java.util.List;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.HashSet;

public final class CxxCognitiveComplexityVisitor<G extends Grammar> extends SquidAstVisitor<G> {

  private final MetricDef metric;
  private final Set<AstNodeType> astNodeTypes;

  public static final class Builder<G extends Grammar> {

    private MetricDef metric;
    private Set<AstNodeType> astNodeTypes = Sets.newHashSet();

    private Builder() {
    }

    public Builder<G> setMetricDef(MetricDef metric) {
      this.metric = metric;
      return this;
    }

    public Builder<G> subscribeTo(AstNodeType... astNodeTypes) {
      this.astNodeTypes.addAll(Arrays.asList(astNodeTypes));
      return this;
    }

    public Builder<G> subscribeTo(Collection<AstNodeType> astNodeTypes) {
      this.astNodeTypes = Sets.newHashSet(astNodeTypes);
      return this;
    }

    public CxxCognitiveComplexityVisitor<G> build() {
      return new CxxCognitiveComplexityVisitor<>(this);
    }

  }

  private static final AstNodeType[] DESCENDANT_TYPES = new AstNodeType[] {
    CxxGrammarImpl.handler,
    CxxGrammarImpl.iterationStatement,
    CxxGrammarImpl.lambdaExpression,
    CxxGrammarImpl.logicalAndExpression,
    CxxGrammarImpl.logicalOrExpression,
    CxxGrammarImpl.selectionStatement,
    CxxKeyword.ELSE,
    CxxKeyword.GOTO,
    CxxPunctuator.QUEST,
    IDENTIFIER
  };

  private static final AstNodeType[] INCREMENT_TYPES = new AstNodeType[] {
    CxxGrammarImpl.handler,
    CxxGrammarImpl.iterationStatement,
    CxxGrammarImpl.logicalAndExpression,
    CxxGrammarImpl.logicalOrExpression,
    CxxGrammarImpl.selectionStatement,
    CxxKeyword.ELSE,
    CxxKeyword.GOTO,
    CxxPunctuator.QUEST
  };

  private static final AstNodeType[] NESTING_LEVEL_TYPES = new AstNodeType[] {
    CxxGrammarImpl.handler,
    CxxGrammarImpl.iterationStatement,
    CxxGrammarImpl.lambdaExpression,
    CxxGrammarImpl.selectionStatement,
    CxxPunctuator.QUEST
  };

  private static final AstNodeType[] NESTING_INCREMENTS_TYPES = new AstNodeType[] {
    CxxGrammarImpl.handler,
    CxxGrammarImpl.iterationStatement,
    CxxGrammarImpl.selectionStatement,
    CxxPunctuator.QUEST
  };

  private int nesting;
  private final Set<AstNode> checkedNodes;
  private AstNode currentFunctionIdentifier;

  private CxxCognitiveComplexityVisitor(Builder<G> builder) {
    this.metric = builder.metric;
    this.astNodeTypes = ImmutableSet.copyOf(builder.astNodeTypes);
    nesting = 0;
    checkedNodes = new HashSet<>();
  }

  public static <G extends Grammar> Builder<G> builder() {
    return new Builder<>();
  }

  @Override
  public void init() {
    for (AstNodeType astNodeType : astNodeTypes) {
      subscribeTo(astNodeType);
    }
  }

  @Override
  public void visitNode(AstNode node) {
    if (checkedNodes.contains(node)) {
      return;
    }
    checkedNodes.add(node);

    if (node.is(CxxGrammarImpl.functionDefinition)) {
      currentFunctionIdentifier = findFunctionIdentifier(node);
    }

    List<AstNode> watchedDescendants = node.getDescendants(DESCENDANT_TYPES);

    if (Arrays.asList(NESTING_LEVEL_TYPES).contains(node.getType()) &&
        !isElseIf(node)) {
      nesting++;
    }

    visitChildren(watchedDescendants);

    if (Arrays.asList(NESTING_LEVEL_TYPES).contains(node.getType()) &&
        !isElseIf(node)) {
      nesting--;
    }

    checkedNodes.addAll(watchedDescendants);

    // For the recursion increment, the token value must match the function token value
    if ((node != currentFunctionIdentifier) &&
        (node.getToken().getValue().equals(currentFunctionIdentifier.getToken().getValue()))) {
      getContext().peekSourceCode().add(metric, 1);
    }

    // For any of the other increment types, just increment when they exist
    if (Arrays.asList(INCREMENT_TYPES).contains(node.getType()) &&
        !isElseIf(node)) {
      getContext().peekSourceCode().add(metric, 1);
    }

    if (Arrays.asList(NESTING_INCREMENTS_TYPES).contains(node.getType()) &&
        !isElseIf(node)) {
      getContext().peekSourceCode().add(metric, nesting);
    }
  }

  private void visitChildren(List<AstNode> watchedDescendants) {
    for (AstNode descendant : watchedDescendants) {
      visitNode(descendant);
    }
  }

  private static boolean isElseIf(AstNode node) {
    return node.is(CxxGrammarImpl.selectionStatement) &&
      node.getToken().getType().equals(CxxKeyword.IF) &&
      node.getParent().getPreviousAstNode().getType().equals(CxxKeyword.ELSE);
  }

  private AstNode findFunctionIdentifier(AstNode node) {
    List<AstNode> identifiers = node.getDescendants(IDENTIFIER);
    for (AstNode identifier : identifiers) {
      if (identifier.hasAncestor(CxxGrammarImpl.functionDeclSpecifierSeq) ||
          identifier.hasAncestor(CxxGrammarImpl.nestedNameSpecifier) ||
          identifier.hasAncestor(CxxGrammarImpl.parametersAndQualifiers) ||
          identifier.hasAncestor(CxxGrammarImpl.functionBody)) continue;
      return identifier;
    }
    return currentFunctionIdentifier;
  }
}
