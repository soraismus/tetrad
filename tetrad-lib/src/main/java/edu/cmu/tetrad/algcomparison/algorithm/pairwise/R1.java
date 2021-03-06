package edu.cmu.tetrad.algcomparison.algorithm.pairwise;

import edu.cmu.tetrad.algcomparison.algorithm.Algorithm;
import edu.cmu.tetrad.algcomparison.utils.TakesInitialGraph;
import edu.cmu.tetrad.annotation.AlgType;
import edu.cmu.tetrad.annotation.Experimental;
import edu.cmu.tetrad.data.DataModel;
import edu.cmu.tetrad.data.DataSet;
import edu.cmu.tetrad.data.DataType;
import edu.cmu.tetrad.data.DataUtils;
import edu.cmu.tetrad.graph.EdgeListGraph;
import edu.cmu.tetrad.graph.Graph;
import edu.cmu.tetrad.search.Lofs2;
import edu.cmu.tetrad.util.Parameters;
import edu.pitt.dbmi.algo.resampling.GeneralResamplingTest;
import edu.pitt.dbmi.algo.resampling.ResamplingEdgeEnsemble;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * R1.
 *
 * @author jdramsey
 */
@Experimental
@edu.cmu.tetrad.annotation.Algorithm(
        name = "R1",
        command = "r1",
        algoType = AlgType.orient_pairwise
)
public class R1 implements Algorithm, TakesInitialGraph {

    static final long serialVersionUID = 23L;
    private Algorithm algorithm = null;
    private Graph initialGraph = null;

    public R1() {
    }

    public R1(Algorithm algorithm) {
        this.algorithm = algorithm;
    }

    @Override
    public Graph search(DataModel dataSet, Parameters parameters) {
    	if (parameters.getInt("numberResampling") < 1) {
            Graph graph = algorithm.search(dataSet, parameters);

            if (graph != null) {
                initialGraph = graph;
            } else {
                throw new IllegalArgumentException("This R1 algorithm needs both data and a graph source as inputs; it \n"
                        + "will orient the edges in the input graph using the data");
            }

            List<DataSet> dataSets = new ArrayList<>();
            dataSets.add(DataUtils.getContinuousDataSet(dataSet));

            Lofs2 lofs = new Lofs2(initialGraph, dataSets);
            lofs.setRule(Lofs2.Rule.R1);

            return lofs.orient();
        } else {
            R1 r1 = new R1(algorithm);
            if (initialGraph != null) {
                r1.setInitialGraph(initialGraph);
            }

            DataSet data = (DataSet) dataSet;
            GeneralResamplingTest search = new GeneralResamplingTest(data, r1, parameters.getInt("numberResampling"));

            search.setResampleSize(parameters.getInt("resampleSize"));
            search.setResamplingWithReplacement(parameters.getBoolean("resamplingWithReplacement"));
            
            ResamplingEdgeEnsemble edgeEnsemble = ResamplingEdgeEnsemble.Highest;
            switch (parameters.getInt("resamplingEnsemble", 1)) {
                case 0:
                    edgeEnsemble = ResamplingEdgeEnsemble.Preserved;
                    break;
                case 1:
                    edgeEnsemble = ResamplingEdgeEnsemble.Highest;
                    break;
                case 2:
                    edgeEnsemble = ResamplingEdgeEnsemble.Majority;
            }
            search.setEdgeEnsemble(edgeEnsemble);
            search.setParameters(parameters);
            search.setVerbose(parameters.getBoolean("verbose"));
            return search.search();
        }
    }

    @Override
    public Graph getComparisonGraph(Graph graph) {
        return new EdgeListGraph(graph);
    }

    @Override
    public String getDescription() {
        return "R1, entropy based pairwise orientation" + (algorithm != null ? " with initial graph from "
                + algorithm.getDescription() : "");
    }

    @Override
    public DataType getDataType() {
        return DataType.Continuous;
    }

    @Override
    public List<String> getParameters() {
        List<String> parameters = new LinkedList<>();

        if (algorithm != null && !algorithm.getParameters().isEmpty()) {
            parameters.addAll(algorithm.getParameters());
        }

        // Resampling
        parameters.add("numberResampling");
        parameters.add("resampleSize");
        parameters.add("resamplingWithReplacement");
        parameters.add("resamplingEnsemble");
        parameters.add("verbose");

        return parameters;
    }

    @Override
    public Graph getInitialGraph() {
        return initialGraph;
    }

    @Override
    public void setInitialGraph(Graph initialGraph) {
        this.initialGraph = initialGraph;
    }

    @Override
    public void setInitialGraph(Algorithm algorithm) {
        if (algorithm == null) {
            throw new IllegalArgumentException("This R1 algorithm needs both data and a graph source as inputs; it \n"
                    + "will orient the edges in the input graph using the data.");
        }

        this.algorithm = algorithm;
    }

}
