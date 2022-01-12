package it.unive.lisa.program.cfg.statement.numeric;

import it.unive.lisa.analysis.AbstractState;
import it.unive.lisa.analysis.AnalysisState;
import it.unive.lisa.analysis.SemanticException;
import it.unive.lisa.analysis.heap.HeapDomain;
import it.unive.lisa.analysis.value.ValueDomain;
import it.unive.lisa.interprocedural.InterproceduralAnalysis;
import it.unive.lisa.program.cfg.CFG;
import it.unive.lisa.program.cfg.CodeLocation;
import it.unive.lisa.program.cfg.statement.Expression;
import it.unive.lisa.symbolic.SymbolicExpression;
import it.unive.lisa.symbolic.value.BinaryExpression;
import it.unive.lisa.symbolic.value.operator.binary.NumericNonOverflowingMul;
import it.unive.lisa.type.NumericType;

/**
 * An expression modeling the multiplication operation ({@code *}). Both
 * operands' types must be instances of {@link NumericType}. The type of this
 * expression is the common numerical type of its operands, according to the
 * type inference.
 * 
 * @author <a href="mailto:luca.negrini@unive.it">Luca Negrini</a>
 */
public class Multiplication extends it.unive.lisa.program.cfg.statement.BinaryExpression {

	/**
	 * Builds the multiplication.
	 * 
	 * @param cfg      the {@link CFG} where this operation lies
	 * @param location the location where this literal is defined
	 * @param left     the left-hand side of this operation
	 * @param right    the right-hand side of this operation
	 */
	public Multiplication(CFG cfg, CodeLocation location, Expression left, Expression right) {
		super(cfg, location, "*", left, right);
	}

	@Override
	protected <A extends AbstractState<A, H, V>,
			H extends HeapDomain<H>,
			V extends ValueDomain<V>> AnalysisState<A, H, V> binarySemantics(
					InterproceduralAnalysis<A, H, V> interprocedural,
					AnalysisState<A, H, V> state,
					SymbolicExpression left,
					SymbolicExpression right)
					throws SemanticException {
		// we allow untyped for the type inference phase
		if (!left.getDynamicType().isNumericType() && !left.getDynamicType().isUntyped())
			return state.bottom();
		if (!right.getDynamicType().isNumericType() && !right.getDynamicType().isUntyped())
			return state.bottom();

		return state.smallStepSemantics(
				new BinaryExpression(
						getRuntimeTypes(),
						left,
						right,
						NumericNonOverflowingMul.INSTANCE,
						getLocation()),
				this);
	}
}
