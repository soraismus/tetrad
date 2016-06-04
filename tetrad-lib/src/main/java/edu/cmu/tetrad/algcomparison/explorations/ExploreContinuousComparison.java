///////////////////////////////////////////////////////////////////////////////
// For information as to what this class does, see the Javadoc, below.       //
// Copyright (C) 1998, 1999, 2000, 2001, 2002, 2003, 2004, 2005, 2006,       //
// 2007, 2008, 2009, 2010, 2014, 2015 by Peter Spirtes, Richard Scheines, Joseph   //
// Ramsey, and Clark Glymour.                                                //
//                                                                           //
// This program is free software; you can redistribute it and/or modify      //
// it under the terms of the GNU General Public License as published by      //
// the Free Software Foundation; either version 2 of the License, or         //
// (at your option) any later version.                                       //
//                                                                           //
// This program is distributed in the hope that it will be useful,           //
// but WITHOUT ANY WARRANTY; without even the implied warranty of            //
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the             //
// GNU General Public License for more details.                              //
//                                                                           //
// You should have received a copy of the GNU General Public License         //
// along with this program; if not, write to the Free Software               //
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA //
///////////////////////////////////////////////////////////////////////////////

package edu.cmu.tetrad.algcomparison.explorations;

import edu.cmu.tetrad.algcomparison.Comparison;
import edu.cmu.tetrad.algcomparison.ComparisonAlgorithm;
import edu.cmu.tetrad.algcomparison.Simulation;
import edu.cmu.tetrad.algcomparison.continuous.pag.ContinuousCfci;
import edu.cmu.tetrad.algcomparison.continuous.pag.ContinuousFci;
import edu.cmu.tetrad.algcomparison.continuous.pag.ContinuousGfci;
import edu.cmu.tetrad.algcomparison.continuous.pag.ContinuousRfci;
import edu.cmu.tetrad.algcomparison.continuous.pattern.ContinuousCpc;
import edu.cmu.tetrad.algcomparison.continuous.pattern.ContinuousFgs;
import edu.cmu.tetrad.algcomparison.continuous.pattern.ContinuousPc;
import edu.cmu.tetrad.algcomparison.continuous.pattern.ContinuousPcs;
import edu.cmu.tetrad.algcomparison.simulation.ContinuousSemSimulation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Joseph Ramsey
 */
public class ExploreContinuousComparison {
    public static void main(String... args) {
        Map<String, Number> parameters = new LinkedHashMap<>();
        parameters.put("numMeasures", 10);
        parameters.put("numLatents", 0);
        parameters.put("maxDegree", 10);
        parameters.put("maxIndegree", 10);
        parameters.put("maxOutdegree", 10);
        parameters.put("connected", 0);
        parameters.put("numEdges", 10);
        parameters.put("sampleSize", 1000);
        parameters.put("minCategoriesForSearch", 2);
        parameters.put("maxCategoriesForSearch", 4);
        parameters.put("numRuns", 5);
        parameters.put("alpha", 0.001);
        parameters.put("penaltyDiscount", 4);
        parameters.put("mgmParam1", 0.1);
        parameters.put("mgmParam2", 0.1);
        parameters.put("mgmParam3", 0.1);
        parameters.put("ofInterestCutoff", 0.05);
        parameters.put("structurePrior", 1);
        parameters.put("samplePrior", 1);

        Map<String, String> stats = new LinkedHashMap<>();
        stats.put("AP", "Adjacency Precision");
        stats.put("AR", "Adjacency Recall");
        stats.put("OP", "Orientation (Arrow) precision");
        stats.put("OR", "Orientation (Arrow) recall");
        stats.put("McAdj", "Matthew's correlation coeffficient for adjacencies");
        stats.put("McOr", "Matthew's correlation coefficient for arrow");
        stats.put("F1Adj", "F1 statistic for adjacencies");
        stats.put("F1Or", "F1 statistic for arrows");
        stats.put("E", "Elapsed time in seconds");

        List<ComparisonAlgorithm> algorithms = new ArrayList<>();
        algorithms.add(new ContinuousPc());
        algorithms.add(new ContinuousCpc());
        algorithms.add(new ContinuousFgs());
        algorithms.add(new ContinuousPcs());
        algorithms.add(new ContinuousFci());
        algorithms.add(new ContinuousRfci());
        algorithms.add(new ContinuousCfci());
        algorithms.add(new ContinuousGfci());

//        Simulation simulation = new LeeHastieSimulation();
        Simulation simulation = new ContinuousSemSimulation();
//        Simulation simulation = new SemThenDiscretizeHalfSimulation();

        new Comparison().testBestAlgorithms(parameters, stats, algorithms, simulation);
    }

}




