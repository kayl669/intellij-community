// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package com.intellij.codeInspection.dataFlow.value;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.codeInspection.dataFlow.*;
import com.intellij.codeInspection.dataFlow.rangeSet.LongRangeSet;
import com.intellij.codeInspection.dataFlow.value.DfaRelationValue.RelationType;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.util.Pair;
import com.intellij.patterns.ElementPattern;
import com.intellij.psi.*;
import com.intellij.psi.util.*;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.FList;
import com.intellij.util.containers.FactoryMap;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.intellij.patterns.PsiJavaPatterns.psiMember;
import static com.intellij.patterns.PsiJavaPatterns.psiParameter;
import static com.intellij.patterns.StandardPatterns.or;

public class DfaValueFactory {
  private final List<DfaValue> myValues = ContainerUtil.newArrayList();
  private final Map<Pair<DfaPsiType, DfaPsiType>, Boolean> myAssignableCache = ContainerUtil.newHashMap();
  private final Map<Pair<DfaPsiType, DfaPsiType>, Boolean> myConvertibleCache = ContainerUtil.newHashMap();
  private final Map<PsiType, DfaPsiType> myDfaTypes = ContainerUtil.newHashMap();
  private final boolean myUnknownMembersAreNullable;
  private final FieldChecker myFieldChecker;

  /**
   * @param context                   an item to analyze (code-block, expression, class)
   * @param unknownMembersAreNullable
   */
  public DfaValueFactory(@Nullable PsiElement context, boolean unknownMembersAreNullable) {
    myFieldChecker = new FieldChecker(context);
    myUnknownMembersAreNullable = unknownMembersAreNullable;
    myValues.add(null);
    myVarFactory = new DfaVariableValue.Factory(this);
    myConstFactory = new DfaConstValue.Factory(this);
    myBoxedFactory = new DfaBoxedValue.Factory(this);
    myRelationFactory = new DfaRelationValue.Factory(this);
    myExpressionFactory = new DfaExpressionFactory(this);
    myFactFactory = new DfaFactMapValue.Factory(this);
  }

  public boolean canTrustFieldInitializer(PsiField field) {
    return myFieldChecker.canTrustFieldInitializer(field);
  }

  private static final ElementPattern<? extends PsiModifierListOwner> MEMBER_OR_METHOD_PARAMETER =
    or(psiMember(), psiParameter().withSuperParent(2, psiMember()));


  @NotNull
  public Nullness suggestNullabilityForNonAnnotatedMember(@NotNull PsiModifierListOwner member) {
    if (myUnknownMembersAreNullable && MEMBER_OR_METHOD_PARAMETER.accepts(member) && AnnotationUtil.getSuperAnnotationOwners(member).isEmpty()) {
      return Nullness.NULLABLE;
    }
    
    return Nullness.UNKNOWN;
  }

  @NotNull
  public DfaValue createTypeValue(@Nullable PsiType type, @NotNull Nullness nullability) {
    if (type == null) return DfaUnknownValue.getInstance();
    DfaFactMap facts = DfaFactMap.EMPTY.with(DfaFactType.TYPE_CONSTRAINT, TypeConstraint.EMPTY.withInstanceofValue(createDfaType(type)))
      .with(DfaFactType.CAN_BE_NULL, NullnessUtil.toBoolean(nullability));
    return getFactFactory().createValue(facts);
  }

  @NotNull
  public <T> DfaValue withFact(@NotNull DfaValue value, @NotNull DfaFactType<T> factType, @Nullable T factValue) {
    if(value instanceof DfaUnknownValue) {
      return getFactFactory().createValue(DfaFactMap.EMPTY.with(factType, factValue));
    }
    if(value instanceof DfaFactMapValue) {
      return ((DfaFactMapValue)value).withFact(factType, factValue);
    }
    return DfaUnknownValue.getInstance();
  }

  @NotNull
  public DfaPsiType createDfaType(@NotNull PsiType psiType) {
    int dimensions = psiType.getArrayDimensions();
    psiType = psiType.getDeepComponentType();
    if (psiType instanceof PsiClassType) {
      psiType = ((PsiClassType)psiType).rawType();
    }
    while (dimensions-- > 0) {
      psiType = psiType.createArrayType();
    }
    DfaPsiType dfaType = myDfaTypes.get(psiType);
    if (dfaType == null) {
      myDfaTypes.put(psiType, dfaType = new DfaPsiType(myDfaTypes.size() + 1, psiType, myAssignableCache, myConvertibleCache));
    }
    return dfaType;
  }

  int registerValue(DfaValue value) {
    myValues.add(value);
    return myValues.size() - 1;
  }

  public DfaValue getValue(int id) {
    return myValues.get(id);
  }

  @NotNull
  public DfaPsiType getType(int id) {
    return StreamEx.ofValues(myDfaTypes).findFirst(t -> t.getID() == id).orElseThrow(IllegalArgumentException::new);
  }

  @Nullable
  public DfaValue createValue(PsiExpression psiExpression) {
    return myExpressionFactory.getExpressionDfaValue(psiExpression);
  }

  @NotNull
  public DfaConstValue getInt(int value) {
    return getConstFactory().createFromValue(value, PsiType.INT, null);
  }

  @Nullable
  public DfaValue createLiteralValue(PsiLiteralExpression literal) {
    return getConstFactory().create(literal);
  }

  /**
   * Create condition (suitable to pass into {@link DfaMemoryState#applyCondition(DfaValue)}),
   * evaluating it statically if possible.
   *
   * @param dfaLeft      left operand
   * @param relationType relation
   * @param dfaRight     right operand
   * @return resulting condition: either {@link DfaRelationValue} or {@link DfaConstValue} (true or false) or {@link DfaUnknownValue}.
   */
  @NotNull
  public DfaValue createCondition(DfaValue dfaLeft, RelationType relationType, DfaValue dfaRight) {
    DfaConstValue value = tryEvaluate(dfaLeft, relationType, dfaRight);
    if (value != null) return value;
    DfaRelationValue relation = getRelationFactory().createRelation(dfaLeft, relationType, dfaRight);
    if (relation != null) return relation;
    return DfaUnknownValue.getInstance();
  }

  @Nullable
  private DfaConstValue tryEvaluate(DfaValue dfaLeft, RelationType relationType, DfaValue dfaRight) {
    if (dfaRight instanceof DfaFactMapValue && dfaLeft == getConstFactory().getNull()) {
      return tryEvaluate(dfaRight, relationType, dfaLeft);
    }
    if (dfaLeft instanceof DfaFactMapValue &&
        dfaRight == getConstFactory().getNull() &&
        Boolean.FALSE.equals(((DfaFactMapValue)dfaLeft).get(DfaFactType.CAN_BE_NULL))) {
      if (relationType == RelationType.EQ) {
        return getConstFactory().getFalse();
      }
      if (relationType == RelationType.NE) {
        return getConstFactory().getTrue();
      }
    }

    if(dfaLeft instanceof DfaFactMapValue && dfaRight instanceof DfaFactMapValue) {
      if(relationType == RelationType.IS || relationType == RelationType.IS_NOT) {
        DfaFactMap leftFacts = ((DfaFactMapValue)dfaLeft).getFacts();
        DfaFactMap rightFacts = ((DfaFactMapValue)dfaRight).getFacts();
        boolean isSuperState = rightFacts.isSuperStateOf(leftFacts);
        if (isSuperState) {
          return getBoolean(relationType == RelationType.IS);
        }
        boolean isDistinct = rightFacts.intersect(leftFacts) == null;
        if (isDistinct) {
          return getBoolean(relationType == RelationType.IS_NOT);
        }
      }
    }

    LongRangeSet leftRange = LongRangeSet.fromDfaValue(dfaLeft);
    LongRangeSet rightRange = LongRangeSet.fromDfaValue(dfaRight);
    if (leftRange != null && rightRange != null) {
      LongRangeSet constraint = rightRange.fromRelation(relationType);
      if (constraint != null && !constraint.intersects(leftRange)) {
        return getConstFactory().getFalse();
      }
      LongRangeSet revConstraint = rightRange.fromRelation(relationType.getNegated());
      if (revConstraint != null && !revConstraint.intersects(leftRange)) {
        return getConstFactory().getTrue();
      }
    }

    if(dfaLeft instanceof DfaConstValue && dfaRight instanceof DfaConstValue &&
       (relationType == RelationType.EQ || relationType == RelationType.NE)) {
      return getBoolean(dfaLeft == dfaRight ^
                        !DfaUtil.isNaN(((DfaConstValue)dfaLeft).getValue()) ^
                        relationType == RelationType.EQ);
    }

    return null;
  }

  public DfaConstValue getBoolean(boolean value) {
    return value ? getConstFactory().getTrue() : getConstFactory().getFalse();
  }

  public <T> DfaValue getFactValue(@NotNull DfaFactType<T> factType, @Nullable T value) {
    return getFactFactory().createValue(factType, value);
  }

  public Collection<DfaValue> getValues() {
    return Collections.unmodifiableCollection(myValues);
  }

  @NotNull
  public DfaControlTransferValue controlTransfer(TransferTarget kind, FList<Trap> traps) {
    return myControlTransfers.get(Pair.create(kind, traps));
  }

  private final Map<Pair<TransferTarget, FList<Trap>>, DfaControlTransferValue> myControlTransfers =
    FactoryMap.create(p -> new DfaControlTransferValue(this, p.first, p.second));

  private final DfaVariableValue.Factory myVarFactory;
  private final DfaConstValue.Factory myConstFactory;
  private final DfaBoxedValue.Factory myBoxedFactory;
  private final DfaRelationValue.Factory myRelationFactory;
  private final DfaExpressionFactory myExpressionFactory;
  private final DfaFactMapValue.Factory myFactFactory;

  @NotNull
  public DfaVariableValue.Factory getVarFactory() {
    return myVarFactory;
  }

  @NotNull
  public DfaConstValue.Factory getConstFactory() {
    return myConstFactory;
  }
  @NotNull
  public DfaBoxedValue.Factory getBoxedFactory() {
    return myBoxedFactory;
  }

  @NotNull
  public DfaRelationValue.Factory getRelationFactory() {
    return myRelationFactory;
  }

  @NotNull
  public DfaFactMapValue.Factory getFactFactory() {
    return myFactFactory;
  }

  @NotNull
  public DfaExpressionFactory getExpressionFactory() { return myExpressionFactory;}

  private static class ClassInitializationInfo {
    final boolean myCanInstantiateItself;
    final boolean myCtorsCallMethods;
    final boolean mySuperCtorsCallMethods;

    ClassInitializationInfo(@NotNull PsiClass psiClass) {
      // Indirect instantiation via other class is still possible, but hopefully unlikely
      myCanInstantiateItself = StreamEx.of(psiClass.getChildren())
                                       .select(PsiMember.class)
                                       .filter(member -> member.hasModifierProperty(PsiModifier.STATIC))
                                       .flatMap(member -> StreamEx.<PsiElement>ofTree(member, e -> StreamEx.of(e.getChildren())))
                                       .select(PsiNewExpression.class).map(newExpr -> newExpr.getClassReference()).nonNull()
                                       .anyMatch(classRef -> classRef.isReferenceTo(psiClass));
      mySuperCtorsCallMethods =
        !InheritanceUtil.processSupers(psiClass, false, superClass -> !canCallMethodsInConstructors(superClass, true));
      myCtorsCallMethods = canCallMethodsInConstructors(psiClass, false);
    }

    private static boolean canCallMethodsInConstructors(@NotNull PsiClass aClass, boolean virtual) {
      for (PsiMethod constructor : aClass.getConstructors()) {
        if (!constructor.getLanguage().isKindOf(JavaLanguage.INSTANCE)) return true;

        PsiCodeBlock body = constructor.getBody();
        if (body == null) continue;

        for (PsiMethodCallExpression call : SyntaxTraverser.psiTraverser().withRoot(body).filter(PsiMethodCallExpression.class)) {
          PsiReferenceExpression methodExpression = call.getMethodExpression();
          if (methodExpression.textMatches(PsiKeyword.THIS) || methodExpression.textMatches(PsiKeyword.SUPER)) continue;
          if (!virtual) return true;

          PsiMethod target = call.resolveMethod();
          if (target != null && PsiUtil.canBeOverridden(target)) return true;
        }
      }

      return false;
    }
  }

  private static class FieldChecker {
    private final boolean myTrustDirectFieldInitializers;
    private final boolean myTrustFieldInitializersInConstructors;
    private final boolean myCanInstantiateItself;
    private final PsiClass myClass;

    FieldChecker(PsiElement context) {
      PsiMethod method = context instanceof PsiClass ? null : PsiTreeUtil.getParentOfType(context, PsiMethod.class);
      myClass = method == null ? null : method.getContainingClass();
      if (myClass == null) {
        myTrustDirectFieldInitializers = myTrustFieldInitializersInConstructors = myCanInstantiateItself = false;
        return;
      }
      // Indirect instantiation via other class is still possible, but hopefully unlikely
      ClassInitializationInfo info = CachedValuesManager.getCachedValue(myClass, () -> CachedValueProvider.Result
        .create(new ClassInitializationInfo(myClass), PsiModificationTracker.MODIFICATION_COUNT));
      myCanInstantiateItself = info.myCanInstantiateItself;
      if (method.hasModifierProperty(PsiModifier.STATIC) || method.isConstructor()) {
        myTrustDirectFieldInitializers = true;
        myTrustFieldInitializersInConstructors = false;
        return;
      }
      myTrustFieldInitializersInConstructors = !info.mySuperCtorsCallMethods && !info.myCtorsCallMethods;
      myTrustDirectFieldInitializers = !info.mySuperCtorsCallMethods;
    }

    boolean canTrustFieldInitializer(PsiField field) {
      if (field.hasInitializer()) {
        boolean staticField = field.hasModifierProperty(PsiModifier.STATIC);
        if (staticField && myClass != null && field.getContainingClass() != myClass) return true;
        return myTrustDirectFieldInitializers && (!myCanInstantiateItself || !staticField);
      }
      return myTrustFieldInitializersInConstructors;
    }
  }
}
