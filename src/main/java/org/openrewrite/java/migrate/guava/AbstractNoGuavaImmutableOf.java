/*
 * Copyright 2023 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openrewrite.java.migrate.guava;

import static java.util.Collections.emptyList;
import static java.util.Objects.nonNull;

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesJavaVersion;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;

import java.time.Duration;
import java.util.Objects;
import java.util.stream.Collectors;
import org.openrewrite.marker.Markers;

abstract class AbstractNoGuavaImmutableOf extends Recipe {

  private final String guavaType;
  private final String javaType;
  private boolean methodUpdated = false;

  AbstractNoGuavaImmutableOf(String guavaType, String javaType) {
    this.guavaType = guavaType;
    this.javaType = javaType;
  }

  private String getShortType(String fullyQualifiedType) {
    return fullyQualifiedType.substring(javaType.lastIndexOf(".") + 1);
  }

  @Override
  public String getDisplayName() {
    return "Prefer `" + getShortType(javaType) + ".of(..)` in Java 9 or higher";
  }

  @Override
  public String getDescription() {
    return "Replaces `" + getShortType(guavaType) +
        ".of(..)` if the returned type is immediately down-cast.";
  }

  @Override
  public Duration getEstimatedEffortPerOccurrence() {
    return Duration.ofMinutes(10);
  }

  @Override
  public TreeVisitor<?, ExecutionContext> getVisitor() {
    TreeVisitor<?, ExecutionContext> check = Preconditions.and(new UsesJavaVersion<>(9),
        new UsesType<>(guavaType, false));
    final MethodMatcher IMMUTABLE_MATCHER = new MethodMatcher(guavaType + " of(..)");
    return Preconditions.check(check, new JavaVisitor<ExecutionContext>() {
      @Override
      public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
        J.MethodInvocation mi = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
        if (!IMMUTABLE_MATCHER.matches(mi) || !isParentTypeDownCast(mi)) {
          return mi;
        }
        maybeRemoveImport(guavaType);
        maybeAddImport(javaType);

        String template = mi.getArguments().stream()
            .map(arg -> {
              if (arg.getType() instanceof JavaType.Primitive) {
                String type = "";
                if (JavaType.Primitive.Boolean == arg.getType()) {
                  type = "Boolean";
                } else if (JavaType.Primitive.Byte == arg.getType()) {
                  type = "Byte";
                } else if (JavaType.Primitive.Char == arg.getType()) {
                  type = "Character";
                } else if (JavaType.Primitive.Double == arg.getType()) {
                  type = "Double";
                } else if (JavaType.Primitive.Float == arg.getType()) {
                  type = "Float";
                } else if (JavaType.Primitive.Int == arg.getType()) {
                  type = "Integer";
                } else if (JavaType.Primitive.Long == arg.getType()) {
                  type = "Long";
                } else if (JavaType.Primitive.Short == arg.getType()) {
                  type = "Short";
                } else if (JavaType.Primitive.String == arg.getType()) {
                  type = "String";
                }
                return TypeUtils.asFullyQualified(JavaType.buildType("java.lang." + type));
              } else {
                return TypeUtils.asFullyQualified(arg.getType());
              }
            })
            .filter(Objects::nonNull)
            .map(type -> "#{any(" + type.getFullyQualifiedName() + ")}")
            .collect(Collectors.joining(",", getShortType(javaType) + ".of(", ")"));

        methodUpdated = true;
        J.MethodInvocation m = JavaTemplate.builder(template)
            .contextSensitive()
            .imports(javaType)
            .build()
            .apply(getCursor(),
                mi.getCoordinates().replace(),
                mi.getArguments().get(0) instanceof J.Empty ? new Object[] {} :
                    mi.getArguments().toArray());
        J.MethodInvocation p = getCursor().getValue();
        m = m.withMethodType((JavaType.Method) visitType(p.getMethodType(), ctx));
        return super.visitMethodInvocation(m, ctx);
      }
      @Override
      public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable,
                                                              ExecutionContext ctx) {
        methodUpdated = false;
        J.VariableDeclarations mv =
            (J.VariableDeclarations) super.visitVariableDeclarations(multiVariable, ctx);

        if (methodUpdated && TypeUtils.isOfClassType(mv.getType(), guavaType)) {
          JavaType newType = JavaType.buildType(javaType);
          mv = mv.withTypeExpression(mv.getTypeExpression() == null ?
              null :
              createNewTypeExpression(mv.getTypeExpression(), newType)
          );

          mv = mv.withVariables(ListUtils.map(mv.getVariables(), variable -> {
            JavaType.FullyQualified varType = TypeUtils.asFullyQualified(variable.getType());
            if (nonNull(varType) && !varType.equals(newType)) {
              return variable.withType(newType).withName(variable.getName().withType(newType));
            }
            return variable;
          }));
        }

        return mv;
      }

      @NotNull
      private TypeTree createNewTypeExpression(TypeTree typeTree,
                                                             JavaType newType) {
        if (typeTree instanceof J.ParameterizedType) {
          List<Expression> parameterizedTypes =
              ((J.ParameterizedType)typeTree).getTypeParameters();
          List<JRightPadded<Expression>> jRightPaddedList = new ArrayList<>();
          parameterizedTypes.forEach(
              expression -> {
                if(expression instanceof J.ParameterizedType && TypeUtils.isOfClassType(expression.getType(), guavaType)) {
                  jRightPaddedList.add(JRightPadded.build(((J.ParameterizedType)createNewTypeExpression((TypeTree) expression, newType))));
                } else {
                  jRightPaddedList.add(JRightPadded.build(expression));
                }
              });
          JContainer<Expression> typeParameters = JContainer.build(jRightPaddedList);
          NameTree clazz =
              new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, emptyList(),
                  getShortType(javaType), null, null);
          return new J.ParameterizedType(
              typeTree.getId(),
              typeTree.getPrefix(),
              typeTree.getMarkers(),
              clazz,
              typeParameters,
              newType
          );
        }
        return new J.Identifier(typeTree.getId(),
            typeTree.getPrefix(),
            Markers.EMPTY,
            emptyList(),
            getShortType(javaType),
            newType,
            null
        );
      }


      private boolean isParentTypeDownCast(MethodCall immutableMethod) {
        J parent = getCursor().dropParentUntil(J.class::isInstance).getValue();
        boolean isParentTypeDownCast = false;
        if (parent instanceof J.VariableDeclarations.NamedVariable) {
          isParentTypeDownCast =
              isParentTypeMatched(((J.VariableDeclarations.NamedVariable) parent).getType());
        } else if (parent instanceof J.Assignment) {
          J.Assignment a = (J.Assignment) parent;
          if (a.getVariable() instanceof J.Identifier &&
              ((J.Identifier) a.getVariable()).getFieldType() != null) {
            isParentTypeDownCast =
                isParentTypeMatched(((J.Identifier) a.getVariable()).getFieldType().getType());
          } else if (a.getVariable() instanceof J.FieldAccess) {
            isParentTypeDownCast = isParentTypeMatched(a.getVariable().getType());
          }
        } else if (parent instanceof J.Return) {
          // Does not currently support returns in lambda expressions.
          J j = getCursor().dropParentUntil(
                  is -> is instanceof J.MethodDeclaration || is instanceof J.CompilationUnit)
              .getValue();
          if (j instanceof J.MethodDeclaration) {
            TypeTree returnType = ((J.MethodDeclaration) j).getReturnTypeExpression();
            if (returnType != null) {
              isParentTypeDownCast = isParentTypeMatched(returnType.getType());
            }
          }
        } else if (parent instanceof J.MethodInvocation) {
          J.MethodInvocation m = (J.MethodInvocation) parent;
          int index = m.getArguments().indexOf(immutableMethod);
          if (m.getMethodType() != null && index != -1 &&
              !m.getMethodType().getParameterTypes().isEmpty()) {
            isParentTypeDownCast =
                isParentTypeMatched(m.getMethodType().getParameterTypes().get(index));
          } else {
            isParentTypeDownCast = true;
          }
        } else if (parent instanceof J.NewClass) {
          J.NewClass c = (J.NewClass) parent;
          int index = 0;
          if (c.getConstructorType() != null) {
            for (Expression argument : c.getArguments()) {
              if (IMMUTABLE_MATCHER.matches(argument)) {
                break;
              }
              index++;
            }
            if (c.getConstructorType() != null) {
              isParentTypeDownCast =
                  isParentTypeMatched(c.getConstructorType().getParameterTypes().get(index));
            }
          }
        } else if (parent instanceof J.NewArray) {
          J.NewArray a = (J.NewArray) parent;
          JavaType arrayType = a.getType();
          while (arrayType instanceof JavaType.Array) {
            arrayType = ((JavaType.Array) arrayType).getElemType();
          }

          isParentTypeDownCast = isParentTypeMatched(arrayType);
        }
        return isParentTypeDownCast;
      }

      private boolean isParentTypeMatched(@Nullable JavaType type) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        return TypeUtils.isOfClassType(fq, javaType) ||
            TypeUtils.isOfClassType(fq, "java.lang.Object") ||
            TypeUtils.isOfClassType(fq, guavaType);
      }
    });
  }
}
