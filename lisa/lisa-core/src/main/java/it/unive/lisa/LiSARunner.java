package it.unive.lisa;

import static it.unive.lisa.LiSAFactory.getInstance;

import it.unive.lisa.analysis.AbstractState;
import it.unive.lisa.analysis.AnalysisState;
import it.unive.lisa.analysis.CFGWithAnalysisResults;
import it.unive.lisa.analysis.SemanticException;
import it.unive.lisa.analysis.heap.HeapDomain;
import it.unive.lisa.analysis.value.ValueDomain;
import it.unive.lisa.caches.Caches;
import it.unive.lisa.checks.ChecksExecutor;
import it.unive.lisa.checks.semantic.CheckToolWithAnalysisResults;
import it.unive.lisa.checks.syntactic.CheckTool;
import it.unive.lisa.checks.warnings.Warning;
import it.unive.lisa.interprocedural.InterproceduralAnalysis;
import it.unive.lisa.interprocedural.InterproceduralAnalysisException;
import it.unive.lisa.interprocedural.callgraph.CallGraph;
import it.unive.lisa.interprocedural.callgraph.CallGraphConstructionException;
import it.unive.lisa.logging.IterationLogger;
import it.unive.lisa.logging.TimerLogger;
import it.unive.lisa.program.Program;
import it.unive.lisa.program.ProgramValidationException;
import it.unive.lisa.program.SyntheticLocation;
import it.unive.lisa.program.cfg.CFG;
import it.unive.lisa.program.cfg.edge.Edge;
import it.unive.lisa.program.cfg.statement.Expression;
import it.unive.lisa.program.cfg.statement.Statement;
import it.unive.lisa.symbolic.value.Skip;
import it.unive.lisa.type.Type;
import it.unive.lisa.util.collections.externalSet.ExternalSet;
import it.unive.lisa.util.datastructures.graph.GraphVisitor;
import it.unive.lisa.util.datastructures.graph.algorithms.FixpointException;
import it.unive.lisa.util.file.FileManager;
import java.io.IOException;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * An auxiliary analysis runner for executing LiSA analysis.
 * 
 * @author <a href="mailto:luca.negrini@unive.it">Luca Negrini</a>
 * 
 * @param <A>  the type of {@link AbstractState} contained into the analysis
 *                 state that will be used in the analysis fixpoint
 * @param <H>  the type of {@link HeapDomain} contained into the abstract state
 *                 that will be used in the analysis fixpoint
 * @param <V>  the type of {@link ValueDomain} contained into the abstract state
 *                 that will be used in the analysis fixpoint
 * @param <T>  the type of {@link AbstractState} contained into the analysis
 *                 state that will be used in the type inference fixpoint
 * @param <HT> the type of {@link HeapDomain} contained into the abstract state
 *                 that will be used in the type inference fixpoint
 * @param <VT> the type of {@link ValueDomain} contained into the abstract state
 *                 that will be used in the type inference fixpoint
 */
public class LiSARunner<A extends AbstractState<A, H, V>,
		H extends HeapDomain<H>,
		V extends ValueDomain<V>,
		T extends AbstractState<T, HT, VT>,
		HT extends HeapDomain<HT>,
		VT extends ValueDomain<VT>> {

	private static final String FIXPOINT_EXCEPTION_MESSAGE = "Exception during fixpoint computation";

	private static final Logger LOG = LogManager.getLogger(LiSARunner.class);

	private final LiSAConfiguration conf;

	private final InterproceduralAnalysis<A, H, V> interproc;

	private final CallGraph callGraph;

	private final A state;

	private final T typeState;

	private final Function<T, ExternalSet<Type>> typeExtractor;

	/**
	 * Builds the runner.
	 * 
	 * @param conf          the configuration of the analysis
	 * @param interproc     the interprocedural analysis to use
	 * @param callGraph     the call graph to use
	 * @param state         the abstract state to use for the analysis
	 * @param typeState     the abstract state to use for type inference
	 * @param typeExtractor the abstract state to use the function that can
	 *                          extract runtime types from {@code typeState}
	 *                          instances
	 */
	LiSARunner(LiSAConfiguration conf, InterproceduralAnalysis<A, H, V> interproc, CallGraph callGraph,
			A state, T typeState, Function<T, ExternalSet<Type>> typeExtractor) {
		this.conf = conf;
		this.interproc = interproc;
		this.callGraph = callGraph;
		this.state = state;
		this.typeState = typeState;
		this.typeExtractor = typeExtractor;
	}

	/**
	 * Executes the runner on the target program.
	 * 
	 * @param program     the program to analyze
	 * @param fileManager the file manager for the analysis
	 * 
	 * @return the warnings generated by the analysis
	 */
	Collection<Warning> run(Program program, FileManager fileManager) {
		finalizeProgram(program);

		Collection<CFG> allCFGs = program.getAllCFGs();

		if (conf.isDumpCFGs())
			for (CFG cfg : IterationLogger.iterate(LOG, allCFGs, "Dumping input CFGs", "cfgs"))
				dumpCFG(fileManager, "", cfg, st -> "");

		CheckTool tool = new CheckTool();
		if (!conf.getSyntacticChecks().isEmpty())
			ChecksExecutor.executeAll(tool, program, conf.getSyntacticChecks());
		else
			LOG.warn("Skipping syntactic checks execution since none have been provided");

		try {
			callGraph.init(program);
		} catch (CallGraphConstructionException e) {
			LOG.fatal("Exception while building the call graph for the input program", e);
			throw new AnalysisExecutionException("Exception while building the call graph for the input program", e);
		}

		try {
			interproc.init(program, callGraph);
		} catch (InterproceduralAnalysisException e) {
			LOG.fatal("Exception while building the interprocedural analysis for the input program", e);
			throw new AnalysisExecutionException(
					"Exception while building the interprocedural analysis for the input program", e);
		}

		if (conf.isInferTypes())
			inferTypes(fileManager, program, allCFGs);
		else
			LOG.warn("Type inference disabled: dynamic type information will not be available for following analysis");

		if (state != null) {
			analyze(allCFGs, fileManager);
			Map<CFG, Collection<CFGWithAnalysisResults<A, H, V>>> results = new IdentityHashMap<>(allCFGs.size());
			for (CFG cfg : allCFGs)
				results.put(cfg, interproc.getAnalysisResultsOf(cfg));

			if (!conf.getSemanticChecks().isEmpty()) {
				CheckToolWithAnalysisResults<A, H,
						V> toolWithResults = new CheckToolWithAnalysisResults<>(tool, results);
				tool = toolWithResults;
				ChecksExecutor.executeAll(toolWithResults, program, conf.getSemanticChecks());
			} else
				LOG.warn("Skipping semantic checks execution since none have been provided");
		} else
			LOG.warn("Skipping analysis execution since no abstract sate has been provided");

		return tool.getWarnings();
	}

	private void analyze(Collection<CFG> allCFGs, FileManager fileManager) {
		A state = this.state.top();
		TimerLogger.execAction(LOG, "Computing fixpoint over the whole program",
				() -> {
					try {
						interproc.fixpoint(new AnalysisState<>(state, new Skip(SyntheticLocation.INSTANCE)),
								conf.getFixpointWorkingSet(), conf.getWideningThreshold());
					} catch (FixpointException e) {
						LOG.fatal(FIXPOINT_EXCEPTION_MESSAGE, e);
						throw new AnalysisExecutionException(FIXPOINT_EXCEPTION_MESSAGE, e);
					}
				});

		if (conf.isDumpAnalysis())
			for (CFG cfg : IterationLogger.iterate(LOG, allCFGs, "Dumping analysis results", "cfgs")) {
				for (CFGWithAnalysisResults<A, H, V> result : interproc.getAnalysisResultsOf(cfg))
					dumpCFG(fileManager,
							"analysis___" + (result.getId() == null ? "" : result.getId().hashCode() + "_"), result,
							st -> result.getAnalysisStateAfter(st).toString());
			}
	}

	@SuppressWarnings("unchecked")
	private void inferTypes(FileManager fileManager, Program program, Collection<CFG> allCFGs) {
		T typesState = this.typeState.top();
		InterproceduralAnalysis<T, HT, VT> typesInterproc;
		try {
			typesInterproc = getInstance(interproc.getClass());
			typesInterproc.init(program, callGraph);
		} catch (AnalysisSetupException | InterproceduralAnalysisException e) {
			throw new AnalysisExecutionException("Unable to initialize type inference", e);
		}

		TimerLogger.execAction(LOG, "Computing type information",
				() -> {
					try {
						typesInterproc.fixpoint(new AnalysisState<>(typesState, new Skip(SyntheticLocation.INSTANCE)),
								conf.getFixpointWorkingSet(), conf.getWideningThreshold());
					} catch (FixpointException e) {
						LOG.fatal(FIXPOINT_EXCEPTION_MESSAGE, e);
						throw new AnalysisExecutionException(FIXPOINT_EXCEPTION_MESSAGE, e);
					}
				});

		String message = conf.isDumpTypeInference()
				? "Dumping type analysis and propagating it to cfgs"
				: "Propagating type information to cfgs";
		for (CFG cfg : IterationLogger.iterate(LOG, allCFGs, message, "cfgs")) {
			Collection<CFGWithAnalysisResults<T, HT, VT>> results = typesInterproc.getAnalysisResultsOf(cfg);
			if (results.isEmpty()) {
				LOG.warn("No type information computed for '{}': it is unreachable", cfg);
				continue;
			}

			CFGWithAnalysisResults<T, HT, VT> result = null;
			try {
				for (CFGWithAnalysisResults<T, HT, VT> res : results)
					if (result == null)
						result = res;
					else
						result = result.join(res);
			} catch (SemanticException e) {
				throw new AnalysisExecutionException("Unable to compute type information for " + cfg, e);
			}

			cfg.accept(new TypesPropagator(), result);

			CFGWithAnalysisResults<T, HT, VT> r = result;
			if (conf.isDumpTypeInference())
				dumpCFG(fileManager, "typing___", r, st -> r.getAnalysisStateAfter(st).toString());
		}
	}

	private class TypesPropagator
			implements
			GraphVisitor<CFG, Statement, Edge, CFGWithAnalysisResults<T, HT, VT>> {

		@Override
		public boolean visit(CFGWithAnalysisResults<T, HT, VT> tool, CFG graph) {
			return true;
		}

		@Override
		public boolean visit(CFGWithAnalysisResults<T, HT, VT> tool, CFG graph, Edge edge) {
			return true;
		}

		@Override
		public boolean visit(CFGWithAnalysisResults<T, HT, VT> tool, CFG graph,
				Statement node) {
			if (tool != null && node instanceof Expression)
				((Expression) node).setRuntimeTypes(typeExtractor.apply(tool.getAnalysisStateAfter(node).getState()));
			return true;
		}
	}

	private static void finalizeProgram(Program program) {
		// fill up the types cache by side effect on an external set
		Caches.types().clear();
		ExternalSet<Type> types = Caches.types().mkEmptySet();
		program.getRegisteredTypes().forEach(types::add);
		types = null;

		TimerLogger.execAction(LOG, "Finalizing input program", () -> {
			try {
				program.validateAndFinalize();
			} catch (ProgramValidationException e) {
				throw new AnalysisExecutionException("Unable to finalize target program", e);
			}
		});
	}

	private static void dumpCFG(FileManager fileManager, String filePrefix, CFG cfg,
			Function<Statement, String> labelGenerator) {
		try {
			fileManager.mkDotFile(filePrefix + cfg.getDescriptor().getFullSignatureWithParNames(),
					writer -> cfg.dump(writer, labelGenerator::apply));
		} catch (IOException e) {
			LOG.error("Exception while dumping the analysis results on {}", cfg.getDescriptor().getFullSignature());
			LOG.error(e);
		}
	}
}
