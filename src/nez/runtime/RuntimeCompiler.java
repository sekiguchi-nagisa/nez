package nez.runtime;

import java.util.HashMap;
import java.util.Map.Entry;

import nez.Production;
import nez.expr.And;
import nez.expr.AnyChar;
import nez.expr.Block;
import nez.expr.ByteChar;
import nez.expr.ByteMap;
import nez.expr.Capture;
import nez.expr.Choice;
import nez.expr.DefIndent;
import nez.expr.DefSymbol;
import nez.expr.Expression;
import nez.expr.Factory;
import nez.expr.IsIndent;
import nez.expr.IsSymbol;
import nez.expr.LeftNew;
import nez.expr.Link;
import nez.expr.New;
import nez.expr.NewClosure;
import nez.expr.NonTerminal;
import nez.expr.Not;
import nez.expr.Option;
import nez.expr.Repetition;
import nez.expr.Repetition1;
import nez.expr.Replace;
import nez.expr.Rule;
import nez.expr.Sequence;
import nez.expr.Tagging;
import nez.main.Verbose;
import nez.util.ConsoleUtils;
import nez.util.FlagUtils;
import nez.util.UList;
import nez.util.UMap;

public class RuntimeCompiler {
	final int option;
	
	class CodeBlock {
		Instruction head;
		int start;
		int end;
	}

	public UList<Instruction> codeList;
	UMap<CodeBlock> ruleMap;
	HashMap<Integer, MemoPoint> memoMap;
	
	public RuntimeCompiler(int option) {
		this.option = option;
		this.codeList = new UList<Instruction>(new Instruction[64]);
		this.ruleMap = new UMap<CodeBlock>();
		if(this.enablePackratParsing()) {
			this.memoMap = new HashMap<Integer, MemoPoint>();
			this.visitedMap = new UMap<String>();
		}
	}
	
	private final boolean enablePackratParsing() {
		return FlagUtils.is(this.option, Production.PackratParsing);
	}

	private final boolean enableASTConstruction() {
		return FlagUtils.is(this.option, Production.ASTConstruction);
	}

	MemoPoint issueMemoPoint(String label, Expression e) {
		if(this.enablePackratParsing()) {
			Integer key = e.getId();
			assert(e.getId() != 0);
			MemoPoint m = this.memoMap.get(key);
			if(m == null) {
				m = new MemoPoint(this.memoMap.size(), label, e, this.isContextSensitive(e));
				this.visitedMap.clear();
				this.memoMap.put(key, m);
			}
			return m;
		}
		return null;
	}

	private UMap<String> visitedMap = null;

	private boolean isContextSensitive(Expression e) {
		if(e instanceof NonTerminal) {
			String un = ((NonTerminal) e).getUniqueName();
			if(visitedMap.get(un) == null) {
				visitedMap.put(un, un);
				return isContextSensitive(((NonTerminal) e).getRule().getExpression());
			}
			return false;
		}
		for(int i = 0; i < e.size(); i++) {
			if(isContextSensitive(e.get(i))) {
				return true;
			}
		}
		return (e instanceof IsIndent || e instanceof IsSymbol);
	}
	
	public final int getInstructionSize() {
		return this.codeList.size();
	}
	
	public final int getMemoPointSize() {
		if(this.enablePackratParsing()) {
			return this.memoMap.size();
		}
		return 0;
	}
	
	public final UList<MemoPoint> getMemoPointList() {
		if(this.memoMap != null) {
			UList<MemoPoint> l = new UList<MemoPoint>(new MemoPoint[this.memoMap.size()]);
			for(Entry<Integer,MemoPoint> e : memoMap.entrySet()) {
				l.add(e.getValue());
			}
			return l;
		}
		return null;
	}
	
	public final Instruction encode(UList<Rule> ruleList) {
		for(Rule r : ruleList) {
			String uname = r.getUniqueName();
			CodeBlock block = new CodeBlock();
			if(Verbose.Debug) {
				Verbose.debug("compiling .. " + r);
			}
			block.head = r.getExpression().encode(this, new IRet(r));
			block.start = codeList.size();
			this.ruleMap.put(uname, block);
			verify(block.head);
			block.end = codeList.size();
		}
		for(Instruction inst : codeList) {
			if(inst instanceof ICallPush) {
				CodeBlock deref = this.ruleMap.get(((ICallPush) inst).rule.getUniqueName());
				((ICallPush) inst).setResolvedJump(deref.head);
			}
		}
		return this.codeList.ArrayValues[0];
	}

	void verify(Instruction inst) {
		if(inst != null) {
			if(inst.id == -1) {
				inst.id = this.codeList.size();
				this.codeList.add(inst);
				verify(inst.next);
				if(inst.next != null && inst.id + 1 != inst.next.id) {
					Instruction.labeling(inst.next);
				}
				verify(inst.branch());
				if(inst instanceof IPrefetch) {
					IPrefetch match = (IPrefetch)inst;
					for(int ch = 0; ch < match.jumpTable.length; ch ++) {
						verify(match.jumpTable[ch]);
					}
				}
				//encode(inst.branch2());
			}
		}
	}
	

	
	public void dump(UList<Rule> ruleList) {
		for(Rule r : ruleList) {
			String uname = r.getUniqueName();
			ConsoleUtils.println(uname + ":");
			CodeBlock block = this.ruleMap.get(uname);
			for(int i = block.start; i < block.end; i++) {
				Instruction inst = codeList.ArrayValues[i];
				if(inst.label) {
					ConsoleUtils.println("" + inst.id + "*\t" + inst);
				}
				else {
					ConsoleUtils.println("" + inst.id + "\t" + inst);
				}
				if(inst.next != null && inst.next.id != i+1) {
					ConsoleUtils.println("\tjump " + Instruction.label(inst.next));
				}
			}
		}
	}
	
	// encoding 

	public final Instruction encodeMatchAny(AnyChar p, Instruction next) {
		
		return new IAnyChar(p, next);
	}

	public final Instruction encodeByteChar(ByteChar p, Instruction next) {
		return new IByteChar(p, false, next);
	}

	public final Instruction encodeByteMap(ByteMap p, Instruction next) {
		return new IByteMap(p, false, next);
	}

	public Instruction encodeFail(Expression p) {
		return new IFail(p);
	}

	public final Instruction encodeRepetition(Repetition p, Instruction next) {
		if(FlagUtils.is(option, Production.Specialization)) {
			Expression inner = p.get(0).optimize(option);
			if(inner instanceof ByteChar) {
				Verbose.noticeOptimize("Specialization", p, inner);
				return new IRepeatedByteMap((ByteChar)inner, next);
			}
			if(inner instanceof ByteMap) {
				Verbose.noticeOptimize("Specialization", p, inner);
				return new IRepeatedByteMap((ByteMap)inner, next);
			}
		}
		IFailSkip skip = p.possibleInfiniteLoop ? new IFailCheckSkip(p) : new IFailSkip(p);
		Instruction start = p.get(0).encode(this, skip);
		skip.next = start;
		return new IFailPush(p, next, start);
	}

	public final Instruction encodeRepetition1(Repetition1 p, Instruction next) {
		return p.get(0).encode(this, this.encodeRepetition(p, next));
	}

	public final Instruction encodeOption(Option p, Instruction next) {
		if(FlagUtils.is(option, Production.Specialization)) {
			Expression inner = p.get(0).optimize(option);
			if(inner instanceof ByteChar) {
				Verbose.noticeOptimize("Specialization", p, inner);
				return new IByteChar((ByteChar)inner, true, next);
			}
			if(inner instanceof ByteMap) {
				Verbose.noticeOptimize("Specialization", p, inner);
				return new IByteMap((ByteMap)inner, true, next);
			}
		}
		Instruction pop = new IFailPop(p, next);
		return new IFailPush(p, next, p.get(0).encode(this, pop));
	}

	public final Instruction encodeAnd(And p, Instruction next) {
		Instruction inner = p.get(0).encode(this, new IPosBack(p, next));
		return new IPosPush(p, inner);
	}

	public final Instruction encodeNot(Not p, Instruction next) {
		if(FlagUtils.is(option, Production.Specialization)) {
			Expression inn = p.get(0).optimize(option);
			if(inn instanceof ByteMap) {
				Verbose.noticeOptimize("Specilization", p);
				return new INotByteMap((ByteMap)inn, next);
			}
			if(inn instanceof ByteChar) {
				Verbose.noticeOptimize("Specilization", p);
				return new INotByteMap((ByteChar)inn, next);
			}
			if(inn instanceof Sequence && ((Sequence)inn).isMultiChar()) {
				Verbose.noticeOptimize("Specilization", p);
				return new INotMultiChar((Sequence)inn, next);
			}
		}
		Instruction fail = new IFailPop(p, new IFail(p));
		return new IFailPush(p, next, p.get(0).encode(this, fail));
	}

	public final Instruction encodeSequence(Expression p, Instruction next) {
		Expression pp = p.optimize(option);
		if(pp != p) {
			if(pp instanceof ByteMap) {
				Verbose.noticeOptimize("ByteMap", p, pp);
				return encodeByteMap((ByteMap)pp, next);
			}
		}
		Instruction nextStart = next;
		for(int i = p.size() -1; i >= 0; i--) {
			Expression e = p.get(i);
			nextStart = e.encode(this, nextStart);
		}
//		if(pp != p) { 	// (!'ab' !'ac' .) => (!'a' .) / (!'ab' !'ac' .)
		if(pp instanceof Choice && pp.get(0) instanceof ByteMap) {
			Verbose.noticeOptimize("Prediction", pp);
			ByteMap notMap = (ByteMap)pp.get(0);
			IPrefetch match = new IPrefetch(p, next);
			Instruction any = new IAnyChar(pp, next);
			for(int ch = 0; ch < notMap.byteMap.length; ch++) {
				if(notMap.byteMap[ch]) {
					match.setJumpTable(ch, any);
				}
				else {
					match.setJumpTable(ch, nextStart);
				}
			}
			return match;
		}
		return nextStart;
	}

	public final Instruction encodeChoice(Choice p, Instruction next) {
		Expression pp = p.optimize(option);
		if(pp instanceof ByteMap) {
			Verbose.noticeOptimize("ByteMap", p, pp);
			return encodeByteMap((ByteMap)pp, next);
		}
		if(p.matchCase != null) {
			return encodePrefetchChoice(p, next);
		}
		return this.encodeUnoptimizedChoice(p, next);
	}
	
	class IPrefetch extends Instruction {
		Instruction[] jumpTable;
		public IPrefetch(Expression e, Instruction next) {
			super(e, next);
			jumpTable = new Instruction[257];
		}
		void setJumpTable(int ch, Instruction inst) {
			jumpTable[ch] = Instruction.labeling(inst);
		}
		@Override
		Instruction exec(Context sc) throws TerminationException {
			int ch = sc.byteAt(sc.getPosition());
			//System.out.println("ch="+(char)ch);
			return jumpTable[ch];
		}
	}
	
	private final Instruction encodePrefetchChoice(Choice p, Instruction next) {
		HashMap<Integer, Instruction> m = new HashMap<>();
		IPrefetch fetch = new IPrefetch(p, null);
		for(int ch = 0; ch < p.matchCase.length; ch++) {
			Expression e = p.matchCase[ch];
			Integer key = e.getId();
			Instruction inst = m.get(key);
			if(inst == null) {
				//System.out.println("creating '" + (char)ch + "'("+ch+"): " + e);
				if(e == p) {
					/* this is a rare case where the selected choice is the parent choice */
					/* this cause the repeated calls of the same matchers */
					Expression common = this.makeCommonPrefix(p);
					if(common != null) {
						//System.out.println("@common '" + (char)ch + "'("+ch+"): " + e + "\n=>\t" + common);
						inst = common.encode(this, next);
					}
					else {
						inst = this.encodeUnoptimizedChoice(p, next);
					}
				}
				else {
					if(e instanceof Choice) {
						Expression common = this.makeCommonPrefix((Choice)e);
						if(common != null) {
							//System.out.println("@common '" + (char)ch + "'("+ch+"): " + e + "\n=>\t" + common);
							inst = common.encode(this, next);
						}
						else {
							inst = this.encodeUnoptimizedChoice((Choice)e, next);
						}
					}
					else {
						inst = e.encode(this, next);
					}
				}
				m.put(key, inst);
			}
			fetch.setJumpTable(ch, inst);
		}
		return fetch;
	}
	
	private final boolean checkStartingTerminal(Expression e, int ch) {
		e = Factory.resolveNonTerminal(e);
		if(e instanceof ByteChar) {
			return (((ByteChar) e).byteChar == ch);
		}
		if(e instanceof Sequence) {
			return checkStartingTerminal(e.get(0), ch);
		}
		if(e instanceof Choice) {
			for(Expression p: e) {
				if(!checkStartingTerminal(p, ch)) {
					return false;
				}
			}
			return true;
		}
		return false;
	}

	private final Expression trimStartingTerminal(Expression e, int ch) {
		if(e instanceof Sequence) {
			UList<Expression> l = new UList<Expression>(new Expression[e.size()-1]);
			for(int i = 1; i < e.size(); i++) {
				l.add(e.get(i));
			}
			return Factory.newSequence(null, l);
		}
		if(e instanceof Choice) {
			UList<Expression> l = new UList<Expression>(new Expression[e.size()]);
			for(Expression p: e) {
				Factory.addChoice(l, p);
			}
			return Factory.newChoice(null, l);
		}
		assert(e instanceof ByteChar);
		return Factory.newEmpty(null);
	}

	private final Expression trimStartingTerminal(Expression e) {
		if(e instanceof Sequence) {
			UList<Expression> l = new UList<Expression>(new Expression[e.size()-1]);
			for(int i = 1; i < e.size(); i++) {
				l.add(e.get(i));
			}
			return Factory.newSequence(null, l);
		}
		if(e instanceof Choice) {
			UList<Expression> l = new UList<Expression>(new Expression[e.size()]);
			for(Expression p: e) {
				Factory.addChoice(l, p);
			}
			return Factory.newChoice(null, l);
		}
		assert(e instanceof ByteChar);
		return Factory.newEmpty(null);
	}

	private final Expression trimCommonPrefix(Expression e, Expression e2) {
		int min = sizeAsSequence(e) < sizeAsSequence(e2) ? sizeAsSequence(e) : sizeAsSequence(e2);
		int commonIndex = -1;
		for(int i = 0; i < min; i++) {
			Expression p = retrieveAsList(e, i);
			Expression p2 = retrieveAsList(e2, i);
			if(p.getId() != p2.getId()) {
				break;
			}
			commonIndex = i + 1;
		}
		if(commonIndex == -1) {
			return null;
		}
		UList<Expression> common = new UList<Expression>(new Expression[commonIndex]);
		for(int i = 0; i < commonIndex; i++) {
			common.add(retrieveAsList(e, i));
		}
		UList<Expression> l1 = new UList<Expression>(new Expression[sizeAsSequence(e)]);
		for(int i = commonIndex; i < sizeAsSequence(e); i++) {
			l1.add(retrieveAsList(e, i));
		}
		UList<Expression> l2 = new UList<Expression>(new Expression[sizeAsSequence(e2)]);
		for(int i = commonIndex; i < sizeAsSequence(e2); i++) {
			l2.add(retrieveAsList(e2, i));
		}
		UList<Expression> l3 = new UList<Expression>(new Expression[2]);
		Factory.addChoice(l3, Factory.newSequence(null, l1));
		Factory.addChoice(l3, Factory.newSequence(null, l2));
		Factory.addSequence(common, Factory.newChoice(null, l3));
		return Factory.newSequence(null, common);
	}

	private final Expression makeCommonPrefix(Choice p) {
		if(!FlagUtils.is(this.option, Production.CommonPrefix)) {
			return null;
		}
		int start = 0;
		Expression common = null;
		for(int i = 0; i < p.size() - 1; i++) {
			Expression e = p.get(i);
			Expression e2 = p.get(i+1);
			if(retrieveAsList(e,0).getId() == retrieveAsList(e2,0).getId()) {
				common = trimCommonPrefix(e, e2);
				start = i;
				break;
			}
		}
		if(common == null) {
			return null;
		}
		UList<Expression> l = new UList<Expression>(new Expression[p.size()]);
		for(int i = 0; i < start; i++) {
			Expression e = p.get(i);
			l.add(e);
		}
		for(int i = start + 2; i < p.size(); i++) {
			Expression e = p.get(i);
			if(retrieveAsList(common, 0).getId() == retrieveAsList(e,0).getId()) {
				e = trimCommonPrefix(common, e);
				if(e != null) {
					common = e;
					continue;
				}
			}
			l.add(common);
			common = e;
		}
		l.add(common);
		return Factory.newChoice(null, l);
	}

	private final int sizeAsSequence(Expression e) {
		if(e instanceof NonTerminal) {
			e = Factory.resolveNonTerminal(e);
		}
		if(e instanceof Sequence) {
			return e.size();
		}
		return 1;
	}

	private final Expression retrieveAsList(Expression e, int index) {
		if(e instanceof NonTerminal) {
			e = Factory.resolveNonTerminal(e);
		}
		if(e instanceof Sequence) {
			return e.get(index);
		}
		return e;
	}

	
	public final Instruction encodeUnoptimizedChoice(Choice p, Instruction next) {
		Instruction nextChoice = p.get(p.size()-1).encode(this, next);
		for(int i = p.size() -2; i >= 0; i--) {
			Expression e = p.get(i);
			nextChoice = new IFailPush(e, nextChoice, e.encode(this, new IFailPop(e, next)));
		}
		return nextChoice;
	}

	public final Instruction encodeNonTerminal(NonTerminal p, Instruction next) {
		Expression pp = p.optimize(option);
		if(pp instanceof ByteChar || pp instanceof ByteMap || pp instanceof AnyChar) {
			Verbose.noticeOptimize("Inlining", p, pp);
			return pp.encode(this, next);
		}
		if(this.enablePackratParsing()) {
			Rule r = p.getRule();
			if(!this.enableASTConstruction() || r.isPurePEG()) {
				Expression ref = Factory.resolveNonTerminal(r.getExpression());
				MemoPoint m = this.issueMemoPoint(r.getUniqueName(), ref);
				if(m != null) {
					if(FlagUtils.is(option, Production.Tracing)) {
						IMonitoredSwitch monitor = new IMonitoredSwitch(p, new ICallPush(p.getRule(), next));
						Instruction inside = new ICallPush(r, newMemoize(p, monitor, m, next));
						monitor.setActivatedNext(newLookup(p, monitor, m, inside, next, newMemoizeFail(p, monitor, m)));
						return monitor;
					}
					Instruction inside = new ICallPush(r, newMemoize(p, IMonitoredSwitch.dummyMonitor, m, next));
					return newLookup(p, IMonitoredSwitch.dummyMonitor, m, inside, next, newMemoizeFail(p, IMonitoredSwitch.dummyMonitor, m));
				}
			}
		}	
		return new ICallPush(p.getRule(), next);
	}
	
	private Instruction newLookup(Expression e, IMonitoredSwitch monitor, MemoPoint m, Instruction next, Instruction skip, Instruction failjump) {
		if(m.contextSensitive) {
			return new IStateLookup(e, monitor, m, next, skip, failjump);
		}
		return new ILookup(e, monitor, m, next, skip, failjump);
	}

	private Instruction newMemoize(Expression e, IMonitoredSwitch monitor, MemoPoint m, Instruction next) {
		if(m.contextSensitive) {
			return new IStateMemoize(e, monitor, m, next);
		}
		return new IMemoize(e, monitor, m, next);
	}

	private Instruction newMemoizeFail(Expression e, IMonitoredSwitch monitor, MemoPoint m) {
		if(m.contextSensitive) {
			return new IStateMemoizeFail(e, monitor, m);
		}
		return new IMemoizeFail(e, monitor, m);
	}

	
	// AST Construction
	
	public final Instruction encodeLink(Link p, Instruction next) {
		if(this.enableASTConstruction()) {
			if(this.enablePackratParsing()) {
				Expression inner = Factory.resolveNonTerminal(p.get(0));
				MemoPoint m = this.issueMemoPoint(p.toString(), inner);
				if(m != null) {
					if(FlagUtils.is(option, Production.Tracing)) {
						IMonitoredSwitch monitor = new IMonitoredSwitch(p, p.get(0).encode(this, next));
						Instruction inside = p.get(0).encode(this, newMemoizeNode(p, monitor, m, next));
						monitor.setActivatedNext(newLookupNode(p, monitor, m, inside, next, new IMemoizeFail(p, monitor, m)));
						return monitor;
					}
					Instruction inside = p.get(0).encode(this, newMemoizeNode(p, IMonitoredSwitch.dummyMonitor, m, next));
					return newLookupNode(p, IMonitoredSwitch.dummyMonitor, m, inside, next, new IMemoizeFail(p, IMonitoredSwitch.dummyMonitor, m));
				}
			}
			return new INodePush(p, p.get(0).encode(this, new INodeStore(p, next)));
		}
		return p.get(0).encode(this, next);
	}

	private Instruction newLookupNode(Link e, IMonitoredSwitch monitor, MemoPoint m, Instruction next, Instruction skip, Instruction failjump) {
		if(m.contextSensitive) {
			return new IStateLookupNode(e, monitor, m, next, skip, failjump);
		}
		return new ILookupNode(e, monitor, m, next, skip, failjump);
	}

	private Instruction newMemoizeNode(Link e, IMonitoredSwitch monitor, MemoPoint m, Instruction next) {
		if(m.contextSensitive) {
			return new IStateMemoizeNode(e, monitor, m, next);
		}
		return new IMemoizeNode(e, monitor, m, next);
	}

	
	public final Instruction encodeNew(NewClosure p, Instruction next) {
		if(this.enableASTConstruction()) {
			return new INew(p, this.encodeSequence(p, new ICapture(p, next)));
		}
		return this.encodeSequence(p, next);
	}

	public final Instruction encodeLeftNew(LeftNew p, Instruction next) {
		if(this.enableASTConstruction()) {
			return new ILeftNew(p, this.encodeSequence(p, new ICapture(p, next)));
		}
		return this.encodeSequence(p, next);
	}

	public final Instruction encodeNew(New p, Instruction next) {
		if(this.enableASTConstruction()) {
			return p.lefted ? new ILeftNew(p, next) : new INew(p, next);
		}
		return next;
	}

	public final Instruction encodeCapture(Capture p, Instruction next) {
		if(this.enableASTConstruction()) {
			return new ICapture(p, next);
		}
		return next;
	}
	
	public final Instruction encodeTagging(Tagging p, Instruction next) {
		if(this.enableASTConstruction()) {
			return new ITag(p, next);
		}
		return next;
	}

	public final Instruction encodeReplace(Replace p, Instruction next) {
		if(this.enableASTConstruction()) {
			return new IReplace(p, next);
		}
		return next;
	}
	
	public final Instruction encodeBlock(Block p, Instruction next) {
		Instruction failed = new ITablePop(p, new IFail(p));
		Instruction inner = p.get(0).encode(this, new IFailPop(p, new ITablePop(p, next)));
		return new ITablePush(p, new IFailPush(p, failed, inner));
	}
	public final Instruction encodeDefSymbol(DefSymbol p, Instruction next) {
		Instruction inner = p.get(0).encode(this, new IDefSymbol(p, next));
		return new IPosPush(p, inner);
	}
	public final Instruction encodeIsSymbol(IsSymbol p, Instruction next) {
		Instruction inner = p.getSymbolExpression().encode(this, new IIsSymbol(p, p.checkLastSymbolOnly, next));
		return new IPosPush(p, inner);
	}
	public final Instruction encodeDefIndent(DefIndent p, Instruction next) {
		return new IDefIndent(p, next);
	}
	public final Instruction encodeIsIndent(IsIndent p, Instruction next) {
		return new IIsIndent(p, next);
	}

}
