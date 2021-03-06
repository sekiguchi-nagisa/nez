package nez.expr;

import nez.SourceContext;
import nez.ast.Node;
import nez.ast.SourcePosition;
import nez.runtime.RuntimeCompiler;
import nez.runtime.Instruction;
import nez.util.UList;
import nez.util.UMap;

public class Repetition extends Unary {
	public boolean possibleInfiniteLoop = false;
	Repetition(SourcePosition s, Expression e) {
		super(s, e);
	}
	@Override
	Expression dupUnary(Expression e) {
		return (this.inner != e) ? Factory.newRepetition(this.s, e) : this;
	}
	@Override
	public String getPredicate() { 
		return "*";
	}
	@Override
	public String getInterningKey() { 
		return "*";
	}
	@Override
	public boolean checkAlwaysConsumed(GrammarChecker checker, String startNonTerminal, UList<String> stack) {
//		if(checker != null) {
//			this.inner.checkAlwaysConsumed(checker, startNonTerminal, stack);
//		}
		return false;
	}
	@Override
	public int inferTypestate(UMap<String> visited) {
		int t = this.inner.inferTypestate(visited);
		if(t == Typestate.ObjectType) {
			return Typestate.BooleanType;
		}
		return t;
	}
	@Override
	public Expression checkTypestate(GrammarChecker checker, Typestate c) {
		int required = c.required;
		if(!this.inner.checkAlwaysConsumed(checker, null, null)) {
			checker.reportError(s, "unconsumed repetition");
			this.possibleInfiniteLoop = true;
		}
		Expression inn = this.inner.checkTypestate(checker, c);
		if(required != Typestate.OperationType && c.required == Typestate.OperationType) {
			checker.reportWarning(s, "unable to create objects in repetition => removed!!");
			this.inner = inn.removeASTOperator();
			c.required = required;
		}
		else {
			this.inner = inn;
		}
		return this;
	}
	@Override public short acceptByte(int ch, int option) {
		short r = this.inner.acceptByte(ch, option);
		if(r == Accept) {
			return Accept;
		}
		return Unconsumed;
	}
	@Override
	public boolean match(SourceContext context) {
		long ppos = -1;
		long pos = context.getPosition();
//		long f = context.rememberFailure();
		while(ppos < pos) {
			Node left = context.left;
			if(!this.inner.optimized.match(context)) {
				context.left = left;
				left = null;
				break;
			}
			ppos = pos;
			pos = context.getPosition();
			left = null;
		}
//		context.forgetFailure(f);
		return true;
	}
	
	@Override
	public Instruction encode(RuntimeCompiler bc, Instruction next) {
		return bc.encodeRepetition(this, next);
	}

	@Override
	protected int pattern(GEP gep) {
		return 2;
	}
	@Override
	protected void examplfy(GEP gep, StringBuilder sb, int p) {
		if(p > 0) {
			int p2 = this.inner.pattern(gep);
			for(int i = 0; i < p2; i++) {
				this.inner.examplfy(gep, sb, p2);
			}
		}
	}
}