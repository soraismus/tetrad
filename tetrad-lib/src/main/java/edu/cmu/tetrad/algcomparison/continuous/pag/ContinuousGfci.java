package edu.cmu.tetrad.algcomparison.continuous.pag;

import edu.cmu.tetrad.algcomparison.ComparisonAlgorithm;
import edu.cmu.tetrad.data.CovarianceMatrixOnTheFly;
import edu.cmu.tetrad.data.DataSet;
import edu.cmu.tetrad.graph.Graph;
import edu.cmu.tetrad.search.*;

import java.util.Map;

/**
 * Created by jdramsey on 6/4/16.
 */
public class ContinuousGfci implements ComparisonAlgorithm {
    public Graph search(DataSet dataSet, Map<String, Number> parameters) {
        Score score = new SemBicScore(new CovarianceMatrixOnTheFly(dataSet));
        GFci pc = new GFci(score);
        return pc.search();
    }

    public String getName() {
        return "GFCI-c";
    }

    @Override
    public Graph getComparisonGraph(Graph dag) {
        return new DagToPag(dag).convert();
    }
}
