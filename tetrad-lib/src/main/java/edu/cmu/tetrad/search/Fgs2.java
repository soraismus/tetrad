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

package edu.cmu.tetrad.search;

import edu.cmu.tetrad.data.*;
import edu.cmu.tetrad.graph.*;
import edu.cmu.tetrad.util.ChoiceGenerator;
import edu.cmu.tetrad.util.ForkJoinPoolInstance;
import edu.cmu.tetrad.util.TetradLogger;

import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.*;


/**
 * GesSearch is an implementation of the GES algorithm, as specified in Chickering (2002) "Optimal structure
 * identification with greedy search" Journal of Machine Learning Research. It works for both BayesNets and SEMs.
 * <p>
 * Some code optimization could be done for the scoring part of the graph for discrete models (method scoreGraphChange).
 * Some of Andrew Moore's approaches for caching sufficient statistics, for instance.
 * <p>
 * To speed things up, it has been assumed that variables X and Y with zero correlation do not correspond to edges in
 * the graph. This is a restricted form of the faithfulness assumption, something GES does not assume. This
 * faithfulness assumption needs to be explicitly turned on using setFaithfulnessAssumed(true).
 * <p>
 * A number of other optimizations were added 5/2015. See code for details.
 *
 * @author Ricardo Silva, Summer 2003
 * @author Joseph Ramsey, Revisions 5/2015
 */
public final class Fgs2 implements GraphSearch, GraphScorer {

    /**
     * Specification of forbidden and required edges.
     */
    private IKnowledge knowledge = new Knowledge2();

    /**
     * List of variables in the data set, in order.
     */
    private List<Node> variables;

    /**
     * The true graph, if known. If this is provided, asterisks will be printed out next to false positive added edges
     * (that is, edges added that aren't adjacencies in the true graph).
     */
    private Graph trueGraph;

    /**
     * An initial graph to start from.
     */
    private Graph initialGraph;

    /**
     * If non-null, edges not adjacent in this graph will not be added.
     */
    private Graph boundGraph = null;

    /**
     * Elapsed time of the most recent search.
     */
    private long elapsedTime;

    /**
     * The depth of search for the forward reevaluation step.
     */
    private int depth = -1;

    /**
     * A bound on cycle length.
     */
    private int cycleBound = -1;

    /**
     * The score for discrete searches.
     */
    private GesScore gesScore;

    /**
     * The logger for this class. The config needs to be set.
     */
    private TetradLogger logger = TetradLogger.getInstance();

    /**
     * The top n graphs found by the algorithm, where n is numPatternsToStore.
     */
    private LinkedList<ScoredGraph> topGraphs = new LinkedList<>();

    /**
     * The number of top patterns to store.
     */
    private int numPatternsToStore = 0;

    /**
     * True if verbose output should be printed.
     */
    private boolean verbose = false;

    // Potential arrows sorted by bump high to low. The first one is a candidate for adding to the graph.
    private SortedSet<Arrow> sortedArrows = null;

    // Arrows added to sortedArrows for each <i, j>.
    private Map<OrderedPair<Node>, Set<Arrow>> lookupArrows = null;

    // A utility map to help with orientation.
    private Map<Node, Set<Node>> neighbors = null;

    // Map from variables to their column indices in the data set.
    private ConcurrentMap<Node, Integer> hashIndices;

    // The static ForkJoinPool instance.
    private ForkJoinPool pool = ForkJoinPoolInstance.getInstance().getPool();

    // A running tally of the total BIC score.
    private double score;

    // A graph where X--Y means that X and Y have non-zero total effect on one another.
    private Graph effectEdgesGraph;

    // The minimum number of operations to do before parallelizing.
    private final int minChunk = 1000;

    // Where printed output is sent.
    private PrintStream out = System.out;

    // A initial adjacencies graph.
    private Graph adjacencies = null;

    // True if it is assumed that zero effect adjacencies are not in the graph.
    private boolean faithfulnessAssumed = false;

    // The graph being constructed.
    private Graph graph;

//     Colliders implied by the Meek rules on the last orientation.
//    private Set<NodePair> impliedColliders = new HashSet<>();

    //===========================CONSTRUCTORS=============================//

    /**
     * The data set must either be all continuous or all discrete.
     */
    public Fgs2(DataSet dataSet) {
        if (verbose) {
            out.println("GES constructor");
        }

        if (dataSet.isDiscrete()) {
            setGesScore(new BDeuScore(dataSet));
        } else {
            setGesScore(new SemBicScore(new CovarianceMatrixOnTheFly(dataSet)));
        }

        if (verbose) {
            out.println("GES constructor done");
        }
    }

    /**
     * Continuous case--where a covariance matrix is already available.
     */
    public Fgs2(ICovarianceMatrix covMatrix) {
        if (verbose) {
            out.println("GES constructor");
        }

        setGesScore(new SemBicScore(covMatrix));

        this.graph = new EdgeListGraphSingleConnections(getVariables());

        if (verbose) {
            out.println("GES constructor done");
        }
    }

    public Fgs2(GesScore gesScore) {
        if (gesScore == null) throw new NullPointerException();
        setGesScore(gesScore);
        this.graph = new EdgeListGraphSingleConnections(getVariables());
    }

    //==========================PUBLIC METHODS==========================//

    /**
     * Set to true if it is assumed that all path pairs with one length 1 path do not cancel.
     */
    public void setFaithfulnessAssumed(boolean faithfulness) {
        this.faithfulnessAssumed = faithfulness;
    }

    /**
     * @return true if it is assumed that all path pairs with one length 1 path do not cancel.
     */
    public boolean isFaithfulnessAssumed() {
        return this.faithfulnessAssumed;
    }

    /**
     * Greedy equivalence search: Start from the empty graph, add edges till model is significant. Then start deleting
     * edges till a minimum is achieved.
     *
     * @return the resulting Pattern.
     */
    public Graph search() {
        lookupArrows = new ConcurrentHashMap<>();
        final List<Node> nodes = new ArrayList<>(variables);

        if (adjacencies != null) {
            adjacencies = GraphUtils.replaceNodes(adjacencies, nodes);
        }

        if (initialGraph == null) {
            graph = new EdgeListGraphSingleConnections(getVariables());
        } else {
            graph.clear();
            graph.transferNodesAndEdges(initialGraph);
            graph = new EdgeListGraphSingleConnections(initialGraph);

            for (Edge edge : initialGraph.getEdges()) {
                if (!effectEdgesGraph.isAdjacentTo(edge.getNode1(), edge.getNode2())) {
                    effectEdgesGraph.addUndirectedEdge(edge.getNode1(), edge.getNode2());
                }
            }
        }

        addRequiredEdges(graph);

        topGraphs.clear();

        storeGraph();

        long start = System.currentTimeMillis();
        score = 0.0;

        // Do forward search.
        fes();

        // Do backward search.
        bes();

        long endTime = System.currentTimeMillis();
        this.elapsedTime = endTime - start;
        this.logger.log("graph", "\nReturning this graph: " + graph);

        this.logger.log("info", "Elapsed time = " + (elapsedTime) / 1000. + " s");
        this.logger.flush();

        return graph;

    }

    /**
     * @return the background knowledge.
     */
    public IKnowledge getKnowledge() {
        return knowledge;
    }

    /**
     * Sets the background knowledge.
     *
     * @param knowledge the knowledge object, specifying forbidden and required edges.
     */
    public void setKnowledge(IKnowledge knowledge) {
        if (knowledge == null) throw new NullPointerException();
        this.knowledge = knowledge;
    }

    public long getElapsedTime() {
        return elapsedTime;
    }

    /**
     * For BIC score, a multiplier on the penalty term. For continuous searches.
     */
    public double getPenaltyDiscount() {
        if (gesScore instanceof SemBicScore) {
            return ((SemBicScore) gesScore).getPenaltyDiscount();
        } else {
            return 2.0;
        }

//        throw new UnsupportedOperationException("Penalty discount supported only for SemBicScore.");
    }

    /**
     * For BIC score, a multiplier on the penalty term. For continuous searches.
     */
    public void setPenaltyDiscount(double penaltyDiscount) {
        if (penaltyDiscount < 0) {
            throw new IllegalArgumentException("Penalty discount must be >= 0: "
                    + penaltyDiscount);
        }

        if (gesScore instanceof SemBicScore) {
            ((SemBicScore) gesScore).setPenaltyDiscount(penaltyDiscount);
        }
//        else {
//            throw new UnsupportedOperationException("Penalty discount supported only for SemBicScore.");
//        }
    }

    /**
     * If the true graph is set, askterisks will be printed in log output for the true edges.
     */
    public void setTrueGraph(Graph trueGraph) {
        this.trueGraph = trueGraph;
    }

    /**
     * @return the score of the given DAG, up to a constant.
     */
    public double getScore(Graph dag) {
        return scoreDag(dag);
    }

    /**
     * @return the list of top scoring graphs.
     */
    public LinkedList<ScoredGraph> getTopGraphs() {
        return topGraphs;
    }

    /**
     * @return the number of patterns to store.
     */
    public int getNumPatternsToStore() {
        return numPatternsToStore;
    }

    /**
     * Sets the number of patterns to store. This should be set to zero for fast search.
     */
    public void setNumPatternsToStore(int numPatternsToStore) {
        if (numPatternsToStore < 0) {
            throw new IllegalArgumentException("# graphs to store must at least 0: " + numPatternsToStore);
        }

        this.numPatternsToStore = numPatternsToStore;
    }

    /**
     * @return the initial graph for the search. The search is initialized to this graph and
     * proceeds from there.
     */
    public Graph getInitialGraph() {
        return initialGraph;
    }

    /**
     * Sets the initial graph.
     */
    public void setInitialGraph(Graph initialGraph) {
        if (initialGraph != null) {
            initialGraph = GraphUtils.replaceNodes(initialGraph, variables);

            if (verbose) {
                out.println("Initial graph variables: " + initialGraph.getNodes());
                out.println("Data set variables: " + variables);
            }

            if (!new HashSet<>(initialGraph.getNodes()).equals(new HashSet<>(variables))) {
                throw new IllegalArgumentException("Variables aren't the same.");
            }
        }

        this.initialGraph = initialGraph;
    }

    /**
     * Sets whether verbose output should be produced.
     */
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    /**
     * Sets the output stream that output (except for log output) should be sent to.
     * By detault System.out.
     */
    public void setOut(PrintStream out) {
        this.out = out;
    }

    /**
     * @return the output stream that output (except for log output) should be sent to.
     */
    public PrintStream getOut() {
        return out;
    }

    /**
     * @return the set of preset adjacenies for the algorithm; edges not in this adjacencies graph
     * will not be added.
     */
    public Graph getAdjacencies() {
        return adjacencies;
    }

    /**
     * Sets the set of preset adjacenies for the algorithm; edges not in this adjacencies graph
     * will not be added.
     */
    public void setAdjacencies(Graph adjacencies) {
        this.adjacencies = adjacencies;
    }

    /**
     * @return the depth for the forward reevaluation step.
     */
    public int getDepth() {
        return depth;
    }

    /**
     * -1 for unlimited depth, otherwise a number >= 0. In the forward reevaluation step, subsets of neighbors up to
     * depth in size are considered. Limiting depth can speed up the algorithm.
     */
    public void setDepth(int depth) {
        this.depth = depth;
    }


    /**
     * A bound on cycle length.
     */
    public int getCycleBound() {
        return cycleBound;
    }

    /**
     * A bound on cycle length.
     *
     * @param cycleBound The bound, >= 1, or -1 for unlimited.
     */
    public void setCycleBound(int cycleBound) {
        if (!(cycleBound == -1 || cycleBound >= 1))
            throw new IllegalArgumentException("Cycle bound needs to be -1 or >= 1: " + cycleBound);
        this.cycleBound = cycleBound;
    }

    /**
     * Creates a new processors pool with the specified number of threads.
     */
    public void setParallelism(int numProcessors) {
        this.pool = new ForkJoinPool(numProcessors);
    }

    /**
     * True iff edges that cause linear dependence are ignored.
     */
    public boolean isIgnoreLinearDependent() {
        if (gesScore instanceof SemBicScore) {
            return ((SemBicScore) gesScore).isIgnoreLinearDependent();
        }

        throw new UnsupportedOperationException("Operation supported only for SemBicScore.");
    }

    public void setIgnoreLinearDependent(boolean ignoreLinearDependent) {
        if (gesScore instanceof SemBicScore) {
            ((SemBicScore) gesScore).setIgnoreLinearDependent(ignoreLinearDependent);
        } else {
            throw new UnsupportedOperationException("Operation supported only for SemBicScore.");
        }
    }

    /**
     * If non-null, edges not adjacent in this graph will not be added.
     */
    public void setBoundGraph(Graph boundGraph) {
        this.boundGraph = GraphUtils.replaceNodes(boundGraph, getVariables());
    }

    //===========================PRIVATE METHODS========================//

    //Sets the discrete scoring function to use.
    private void setGesScore(GesScore gesScore) {
        this.gesScore = gesScore;

        this.variables = new ArrayList<>();

        for (Node node : gesScore.getVariables()) {
            if (node.getNodeType() == NodeType.MEASURED) {
                this.variables.add(node);
            }
        }
    }


    // Simultaneously finds the first edge to add to an empty graph and finds all length 1 paths that are
    // not canceled by other paths (the "effect edges")
    private void sortUnconditionedEdges(final List<Node> nodes) {
        long start = System.currentTimeMillis();
        final Graph effectEdgesGraph = new EdgeListGraphSingleConnections(nodes);
        final Set<Node> emptySet = new HashSet<>(0);

        final int[] count = new int[1];

        class EffectTask extends RecursiveTask<Boolean> {
            private int chunk;
            private int from;
            private int to;

            public EffectTask(int chunk, int from, int to) {
                this.chunk = chunk;
                this.from = from;
                this.to = to;
            }

            @Override
            protected Boolean compute() {
                if (to - from <= chunk) {
                    for (int i = from; i < to; i++) {
                        if (verbose) {
                            synchronized (count) {
                                if (((count[0]++) + 1) % 1000 == 0)
                                    out.println("Initializing effect edges: " + count[0]);
                            }
                        }

                        Node y = nodes.get(i);
                        neighbors.put(y, getNeighbors(y));

                        for (int j = i + 1; j < nodes.size(); j++) {
                            if (i == j) continue;
                            Node x = nodes.get(j);
//
                            if (existsKnowledge()) {
                                if (getKnowledge().isForbidden(x.getName(), y.getName()) && getKnowledge().isForbidden(y.getName(), x.getName())) {
                                    continue;
                                }

                                if (!validSetByKnowledge(y, emptySet)) {
                                    continue;
                                }
                            }

                            if (adjacencies != null && !adjacencies.isAdjacentTo(x, y)) {
                                continue;
                            }

                            int child = hashIndices.get(y);
                            int parent = hashIndices.get(x);
                            double bump = gesScore.localScoreDiff(child, new int[]{}, parent);

                            if (isFaithfulnessAssumed() && gesScore.isEffectEdge(bump)) {
                                final Edge edge = Edges.undirectedEdge(x, y);
                                if (boundGraph != null && !boundGraph.isAdjacentTo(edge.getNode1(), edge.getNode2()))
                                    continue;
                                effectEdgesGraph.addEdge(edge);
                            }

                            if (bump > 0.0) {
                                addArrow(x, y, emptySet, emptySet, bump);
                                addArrow(y, x, emptySet, emptySet, bump);
                            }
                        }
                    }

                    return true;
                } else {
                    int mid = (to - from) / 2;

                    List<EffectTask> tasks = new ArrayList<>();

                    tasks.add(new EffectTask(chunk, from, from + mid));
                    tasks.add(new EffectTask(chunk, from + mid, to));

                    invokeAll(tasks);

                    return true;
                }
            }
        }

        buildIndexing(nodes);
        pool.invoke(new EffectTask(minChunk, 0, nodes.size()));

        long stop = System.currentTimeMillis();

        if (verbose) {
            out.println("Elapsed sortUnconditionedEdges = " + (stop - start) + " ms");
        }

        this.effectEdgesGraph = effectEdgesGraph;
    }

    /**
     * Forward equivalence search.
     */
    private void fes() {
        TetradLogger.getInstance().log("info", "** FORWARD EQUIVALENCE SEARCH");

        sortedArrows = new ConcurrentSkipListSet<>();
        lookupArrows = new ConcurrentHashMap<>();
        neighbors = new ConcurrentHashMap<>();

        // This takes most of the time and calculates all of the effect edges if faithfulness is assumed.
        sortUnconditionedEdges(getVariables());

        while (!sortedArrows.isEmpty()) {
            Arrow arrow = sortedArrows.first();
            sortedArrows.remove(arrow);

            Node x = arrow.getA();
            Node y = arrow.getB();

            if (graph.isAdjacentTo(x, y)) {
                continue;
            }

            if (!arrow.getNaYX().equals(getNaYX(x, y))) {
                continue;
            }

            if (!getTNeighbors(x, y).containsAll(arrow.getHOrT())) {
                continue;
            }

            if (!validInsert(x, y, arrow.getHOrT(), getNaYX(x, y))) {
                continue;
            }

            Set<Node> t = arrow.getHOrT();
            double bump = arrow.getBump();

            if (!insert(x, y, t, bump)) {
                continue;
            }

            score += bump;

            Set<Node> toProcess = reapplyOrientation(x, y);
            storeGraph();
            reevaluateForward(toProcess);
        }
    }

    /**
     * Backward equivalence search.
     */
    private void bes() {
        TetradLogger.getInstance().log("info", "** BACKWARD EQUIVALENCE SEARCH");
        initializeArrowsBackward();

        while (!sortedArrows.isEmpty()) {
            Arrow arrow = sortedArrows.first();
            sortedArrows.remove(arrow);

            Node x = arrow.getA();
            Node y = arrow.getB();

            if (!arrow.getNaYX().equals(getNaYX(x, y))) {
                continue;
            }

            if (!graph.isAdjacentTo(x, y)) continue;

            HashSet<Node> diff = new HashSet<>(arrow.getNaYX());
            diff.removeAll(arrow.getHOrT());

            if (!isClique(diff)) continue;

            Set<Node> h = arrow.getHOrT();
            double bump = arrow.getBump();

            delete(x, y, h, bump, arrow.getNaYX());
            score += bump;

            clearArrow(x, y);

            Set<Node> toProcess = reapplyOrientation(x, y);
            storeGraph();
            reevaluateBackward(toProcess);
        }
    }

    private Set<Node> reapplyOrientation(Node x, Node y) {
        Set<Node> visited = rebuildPatternRestricted(x, y);
        Set<Node> toProcess = new HashSet<>();

        for (Node node : visited) {
            final Set<Node> neighbors = getNeighbors(node);
            final Set<Node> storedNeighbors = this.neighbors.get(node);

            if (!neighbors.equals(storedNeighbors)) {
                toProcess.add(node);
            }
        }

        toProcess.add(x);
        toProcess.add(y);

        toProcess.addAll(graph.getAdjacentNodes(x));
        toProcess.addAll(graph.getAdjacentNodes(y));

        return toProcess;
    }

    // Returns true if knowledge is not empty.
    private boolean existsKnowledge() {
        return false;
//        return !knowledge.isEmpty();
    }

    // Initiaizes the sorted arrows lists for the backward search.
    private void initializeArrowsBackward() {
        for (Edge edge : graph.getEdges()) {
            Node x = edge.getNode1();
            Node y = edge.getNode2();

            if (existsKnowledge()) {
                if (!getKnowledge().noEdgeRequired(x.getName(), y.getName())) {
                    continue;
                }
            }

//            if (Edges.isDirectedEdge(edge)) {
                calculateArrowsBackward(x, y);
//            } else {zx
//                calculateArrowsBackward(x, y);
//                calculateArrowsBackward(y, x);
//            }
        }
    }

    // Calcuates new arrows based on changes in the graph for the forward search.
    private void reevaluateForward(final Set<Node> nodes) {
        class AdjTask extends RecursiveTask<Boolean> {
            private final List<Node> nodes;
            private int from;
            private int to;
            private int chunk;

            public AdjTask(int chunk, List<Node> nodes, int from, int to) {
                this.nodes = nodes;
                this.from = from;
                this.to = to;
                this.chunk = chunk;
            }

            @Override
            protected Boolean compute() {
                if (to - from <= chunk) {
                    for (int _w = from; _w < to; _w++) {
                        Node x = nodes.get(_w);

                        List<Node> adj;

                        if (isFaithfulnessAssumed()) {
                            adj = effectEdgesGraph.getAdjacentNodes(x);
                        } else {
                            adj = getVariables();
                        }

                        for (Node w : adj) {
                            if (adjacencies != null && !(adjacencies.isAdjacentTo(w, x))) {
                                continue;
                            }

                            if (w == x) continue;

                            if (!graph.isAdjacentTo(w, x)) {
                                calculateArrowsForward(w, x);
                                calculateArrowsForward(x, w);
                            }
                        }
                    }

                    return true;
                } else {
                    int mid = (to - from) / 2;

                    List<AdjTask> tasks = new ArrayList<>();

                    tasks.add(new AdjTask(chunk, nodes, from, from + mid));
                    tasks.add(new AdjTask(chunk, nodes, from + mid, to));

                    invokeAll(tasks);

                    return true;
                }
            }
        }

        final AdjTask task = new AdjTask(minChunk, new ArrayList<>(nodes), 0, nodes.size());

        pool.invoke(task);

    }

    // Calculates the new arrows for an a->b edge.
    private void calculateArrowsForward(Node a, Node b) {
        if (isFaithfulnessAssumed() && !effectEdgesGraph.isAdjacentTo(a, b)) return;
        if (adjacencies != null && !adjacencies.isAdjacentTo(a, b)) return;
        this.neighbors.put(b, getNeighbors(b));

        if (existsKnowledge()) {
            if (getKnowledge().isForbidden(a.getName(), b.getName())) {
                return;
            }
        }

        Set<Node> naYX = getNaYX(a, b);
//        Set<Node> naXY = getNaYX(b, a);
//
//        if (naXY.size() > naYX.size()) {
//            Node e = a;
//            a = b;
//            b = e;
//            naYX = naXY;
//        }

        if (!isClique(naYX)) return;

//        final Set<Node> naYX = getNaYX(a, b);

        clearArrow(a, b);

        List<Node> TNeighbors = getTNeighbors(a, b);

        final int _depth = Math.min(TNeighbors.size(), depth == -1 ? 1000 : depth);

        List<Set<Node>> lastSubsets = null;

        for (int i = 0; i <= _depth; i++) {
            final ChoiceGenerator gen = new ChoiceGenerator(TNeighbors.size(), i);
            int[] choice;
//            boolean found = false;
            List<Set<Node>> subsets = new ArrayList<>();

            while ((choice = gen.next()) != null) {
                Set<Node> T = GraphUtils.asSet(choice, TNeighbors);

                Set<Node> union = new HashSet<>(naYX);
                union.addAll(T);

                if (lastSubsets != null) {
                    boolean foundASubset = false;

                    for (Set<Node> set : lastSubsets) {
                        if (T.containsAll(set)) {
                            foundASubset = true;
                            break;
                        }
                    }

                    if (!foundASubset) continue;
                }

                if (!isClique(union)) continue;
                subsets.add(T);

                if (existsKnowledge()) {
                    if (!validSetByKnowledge(b, T)) {
                        continue;
                    }
                }

                double bump = insertEval(a, b, T, naYX, hashIndices);

                if (bump > 0.0) {
                    addArrow(a, b, naYX, T, bump);
//                    found = true;
                }
            }

//            if (i > 0 && !found) break;
            lastSubsets = subsets;
        }
    }

    private void addArrow(Node a, Node b, Set<Node> naYX, Set<Node> hOrT, double bump) {
        Arrow arrow = new Arrow(bump, a, b, hOrT, naYX);
        sortedArrows.add(arrow);
        addLookupArrow(a, b, arrow);
    }

    // Reevaluates arrows after removing an edge from the graph.
    private void reevaluateBackward(Set<Node> toProcess) {
        class BackwardTask extends RecursiveTask<Boolean> {
            private final Node r;
            private List<Node> adj;
            private Map<Node, Integer> hashIndices;
            private int chunk;
            private int from;
            private int to;

            public BackwardTask(Node r, List<Node> adj, int chunk, int from, int to,
                                Map<Node, Integer> hashIndices) {
                this.adj = adj;
                this.hashIndices = hashIndices;
                this.chunk = chunk;
                this.from = from;
                this.to = to;
                this.r = r;
            }

            @Override
            protected Boolean compute() {
                if (to - from <= chunk) {
                    for (int _w = from; _w < to; _w++) {
                        final Node w = adj.get(_w);

                        if (graph.isAdjacentTo(w, r)) {
//                            if (graph.isParentOf(w, r)) {
                                calculateArrowsBackward(w, r);
//                            } else {
//                                calculateArrowsBackward(w, r);
//                                calculateArrowsBackward(r, w);
//                            }
                        }
                    }

                    return true;
                } else {
                    int mid = (to - from) / 2;

                    List<BackwardTask> tasks = new ArrayList<>();

                    tasks.add(new BackwardTask(r, adj, chunk, from, from + mid, hashIndices));
                    tasks.add(new BackwardTask(r, adj, chunk, from + mid, to, hashIndices));

                    invokeAll(tasks);

                    return true;
                }
            }
        }

        for (Node r : toProcess) {
//            System.out.println("To process " + r);
            neighbors.put(r, getNeighbors(r));
            List<Node> adjacentNodes = graph.getAdjacentNodes(r);
            pool.invoke(new BackwardTask(r, adjacentNodes, minChunk, 0, adjacentNodes.size(), hashIndices));
        }
    }

    // Calculates the arrows for the removal in the backward direction.
    private void calculateArrowsBackward(Node a, Node b) {
        if (existsKnowledge()) {
            if (!getKnowledge().noEdgeRequired(a.getName(), b.getName())) {
                return;
            }
        }

        Set<Node> naYX = getNaYX(a, b);
        Set<Node> naXY = getNaYX(b, a);

        if (!graph.isParentOf(b, a) && naXY.containsAll(naYX) && !naYX.equals(naXY)) {
//            Node e = a;
//            a = b;
//            b = e;q
//            naYX = naXY;
            clearArrow(a, b);
            calculateArrowsBackward(b, a);
            return;
        }

//        System.out.println("NaYX for " + a + "-->" + b + " is " + naXY);

        clearArrow(a, b);

        List<Node> _naYX = new ArrayList<>(naYX);

        final int _depth = Math.min(_naYX.size(), depth == -1 ? 1000 : depth);

        List<Set<Node>> lastSubsets = null;

        for (int i = 0; i <= _depth; i++) {
            final ChoiceGenerator gen = new ChoiceGenerator(_naYX.size(), i);
            int[] choice;
            List<Set<Node>> subsets = new ArrayList<>();

            while ((choice = gen.next()) != null) {
                Set<Node> diff = GraphUtils.asSet(choice, _naYX);

                Set<Node> h = new HashSet<>(_naYX);
                h.removeAll(diff);

                if (lastSubsets != null) {
                    boolean foundASubset = false;

                    for (Set<Node> set : lastSubsets) {
                        if (diff.containsAll(set)) {
                            foundASubset = true;
                            break;
                        }
                    }

                    if (!foundASubset) continue;
                }

//                if (!isClique(diff)) continue;
                subsets.add(diff);

                if (existsKnowledge()) {
                    if (!validSetByKnowledge(b, h)) {
                        continue;
                    }
                }

                double bump = deleteEval(a, b, diff, naYX, hashIndices);

                if (bump >= 0.0) {
                    addArrow(a, b, naYX, h, bump);
                }

            }

            lastSubsets = subsets;
        }
    }

    public void setSamplePrior(double samplePrior) {
        if (gesScore instanceof LocalDiscreteScore) {
            ((LocalDiscreteScore) gesScore).setSamplePrior(samplePrior);
        }
    }

    public void setStructurePrior(double expectedNumParents) {
        if (gesScore instanceof LocalDiscreteScore) {
            ((LocalDiscreteScore) gesScore).setStructurePrior(expectedNumParents);
        }
    }

    // Basic data structure for an arrow a->b considered for additiom or removal from the graph, together with
// associated sets needed to make this determination. For both forward and backward direction, NaYX is needed.
// For the forward direction, T neighbors are needed; for the backward direction, H neighbors are needed.
// See Chickering (2002). The score difference resulting from added in the edge (hypothetically) is recorded
// as the "bump".
    private static class Arrow implements Comparable<Arrow> {
        private double bump;
        private Node a;
        private Node b;
        private Set<Node> hOrT;
        private Set<Node> naYX;

        public Arrow(double bump, Node a, Node b, Set<Node> hOrT, Set<Node> naYX) {
            this.bump = bump;
            this.a = a;
            this.b = b;
            this.hOrT = hOrT;
            this.naYX = naYX;
        }

        public double getBump() {
            return bump;
        }

        public Node getA() {
            return a;
        }

        public Node getB() {
            return b;
        }

        public Set<Node> getHOrT() {
            return hOrT;
        }

        public Set<Node> getNaYX() {
            return naYX;
        }

        // Sorting by bump, high to low. The problem is the SortedSet contains won't add a new element if it compares
        // to zero with an existing element, so for the cases where the comparison is to zero (i.e. have the same
        // bump, we need to determine as quickly as possible a determinate ordering (fixed) ordering for two variables.
        // The fastest way to do this is using a hash code, though it's still possible for two Arrows to have the
        // same hash code but not be equal. If we're paranoid, in this case we calculate a determinate comparison
        // not equal to zero by keeping a list. This last part is commened out by default.
        public int compareTo(Arrow arrow) {
            final int compare = Double.compare(arrow.getBump(), getBump());

            if (compare == 0) {
                int hashcode1 = hashCode();
                int hashcode2 = arrow.hashCode();
                return Integer.compare(hashcode1, hashcode2);
            }

            return compare;
        }

        public boolean equals(Object o) {
            if (!(o instanceof Arrow)) {
                return false;
            }

            Arrow a = (Arrow) o;

            return a.a.equals(this.a) && a.b.equals(this.b) && a.hOrT.equals(this.hOrT) && a.naYX.equals(this.naYX);
        }

        public int hashCode() {
            return 11 * a.hashCode() + 13 * b.hashCode() + 17 * hOrT.hashCode() + 19 * naYX.hashCode();
        }

        public String toString() {
            return "Arrow<" + a + "->" + b + " bump = " + bump + " t/h = " + hOrT + " naYX = " + naYX + ">";
        }

    }

    // Get all adj that are connected to Y by an undirected edge and not adjacent to X.
    private List<Node> getTNeighbors(Node x, Node y) {
        List<Edge> yEdges = graph.getEdges(y);
        List<Node> tNeighbors = new ArrayList<>();

        for (Edge edge : yEdges) {
            if (!Edges.isUndirectedEdge(edge)) {
                continue;
            }

            Node z = edge.getDistalNode(y);

            if (graph.isAdjacentTo(z, x)) {
                continue;
            }

            tNeighbors.add(z);
        }

        return tNeighbors;
    }

    // Get all adj that are connected to Y by an undirected edge, except x.
    private Set<Node> getNeighbors(Node x, Node y) {
        List<Edge> yEdges = graph.getEdges(y);
        Set<Node> neighbors = new HashSet<>();

        for (Edge edge : yEdges) {
            if (!Edges.isUndirectedEdge(edge)) {
                continue;
            }

            Node z = edge.getDistalNode(y);

            if (z == x) continue;

            neighbors.add(z);
        }

        return neighbors;
    }

    // Get all adj that are connected to Y.
    private Set<Node> getNeighbors(Node y) {
        List<Edge> yEdges = graph.getEdges(y);
        Set<Node> neighbors = new HashSet<>();

        for (Edge edge : yEdges) {
            if (!Edges.isUndirectedEdge(edge)) {
                continue;
            }

            Node z = edge.getDistalNode(y);

            neighbors.add(z);
        }

        return neighbors;
    }

    // Evaluate the Insert(X, Y, T) operator (Definition 12 from Chickering, 2002).
    private double insertEval(Node x, Node y, Set<Node> t, Set<Node> naYX,
                              Map<Node, Integer> hashIndices) {
        Set<Node> parents = new HashSet<>(graph.getParents(y));

        Set<Node> set = new HashSet<>(naYX);
        set.addAll(t);
        set.addAll(parents);

        return scoreGraphChange(y, set, x, hashIndices);
    }

    // Evaluate the Delete(X, Y, T) operator (Definition 12 from Chickering, 2002).
    private double deleteEval(Node x, Node y, Set<Node> diff, Set<Node> naYX,
                              Map<Node, Integer> hashIndices) {

        if (!naYX.equals(getNaYX(x, y))) {
            throw new IllegalArgumentException();
        }

        Set<Node> parents = new HashSet<>(graph.getParents(y));
        parents.remove(x);

        Set<Node> set = new HashSet<>(diff);
        set.addAll(parents);

        return -scoreGraphChange(y, set, x, hashIndices);
    }

//    Set<Triple> triplesAdded = new HashSet<>();

    // Do an actual insertion. (Definition 12 from Chickering, 2002).
    private boolean insert(Node x, Node y, Set<Node> T, double bump) {
        if (graph.isAdjacentTo(x, y)) {
            return false; // The initial graph may already have put this edge in the graph.
//            throw new IllegalArgumentException(x + " and " + y + " are already adjacent in the graph.");
        }

        Set<Node> union = new HashSet<>(getNaYX(x, y));
        union.addAll(T);

        Edge trueEdge = null;

        if (trueGraph != null) {
            Node _x = trueGraph.getNode(x.getName());
            Node _y = trueGraph.getNode(y.getName());
            trueEdge = trueGraph.getEdge(_x, _y);
        }

        if (boundGraph != null && !boundGraph.isAdjacentTo(x, y)) return false;

        Edge edge = Edges.directedEdge(x, y);

        graph.addEdge(edge);

//        for (Node node : graph.getParents(y)) {
//            triplesAdded.add(new Triple(x, node, y));
//            impliedColliders.remove(new NodePair(x, node));
//            impliedColliders.remove(new NodePair(y, node));
//        }


        if (graph.getEdge(x, y) != edge) {
            throw new IllegalArgumentException();
        }

        if (verbose) {
            String label = trueGraph != null && trueEdge != null ? "*" : "";
            TetradLogger.getInstance().log("insertedEdges", graph.getNumEdges() + ". INSERT " + graph.getEdge(x, y) +
                    " " + T + " " + bump + " " + label);
        }

        int numEdges = graph.getNumEdges();

        if (verbose) {
            if (numEdges % 1000 == 0) out.println("Num edges added: " + numEdges);
        }

        if (verbose) {
            String label = trueGraph != null && trueEdge != null ? "*" : "";
            out.println(graph.getNumEdges() + ". INSERT " + graph.getEdge(x, y) +
                    " " + T + " " + bump + " " + label + " degree = " + GraphUtils.getDegree(graph));
        }

        Set<Node> t2 = new HashSet<>(T);
        t2.addAll(getNaYX(x, y));

        for (Node _t : t2) {
            Edge oldEdge = graph.getEdge(_t, y);

            if (oldEdge == null) throw new IllegalArgumentException("Not adjacent: " + _t + ", " + y);

            graph.removeEdge(oldEdge);
            if (boundGraph != null && !boundGraph.isAdjacentTo(_t, y)) continue;

            Edge newEdge = Edges.directedEdge(_t, y);
            graph.addEdge(newEdge);

            if (verbose) {
                String message = "--- Directing " + oldEdge + " to " + newEdge;
                TetradLogger.getInstance().log("directedEdges", message);
                out.println(message);
            }
        }

        return true;
    }

    // Do an actual deletion (Definition 13 from Chickering, 2002).
    private void delete(Node x, Node y, Set<Node> H, double bump, Set<Node> naYX) {
        Edge trueEdge = null;

        if (trueGraph != null) {
            Node _x = trueGraph.getNode(x.getName());
            Node _y = trueGraph.getNode(y.getName());
            trueEdge = trueGraph.getEdge(_x, _y);
        }

        Edge oldxy = graph.getEdge(x, y);

        Set<Node> diff = new HashSet<>(naYX);
        diff.removeAll(H);

//        Set<Node> diff2 = new HashSet<>(getNaYX2(x, y));
//        diff2.removeAll(diff);
//
//        H.addAll(diff);

        graph.removeEdge(oldxy);

//        rebuildPatternRestricted(x, y);

        if (verbose) {
            int numEdges = graph.getNumEdges();
            if (numEdges % 1000 == 0) out.println("Num edges (backwards) = " + numEdges);

            String label = trueGraph != null && trueEdge != null ? "*" : "";
            String message = (graph.getNumEdges()) + ". DELETE " + x + "-->" + y +
                    " H = " + H + " NaYX = " + naYX + " diff = " + diff + " (" + bump + ") " + label;
            TetradLogger.getInstance().log("deletedEdges", message);
            out.println(message);
        }

//        if (isClique(diff)) {
        for (Node h : H) {
            if (graph.isParentOf(h, y) || graph.isParentOf(h, x)) continue;

            Edge oldyh = graph.getEdge(y, h);

//                if (!Edges.isUndirectedEdge(oldyh)) throw new IllegalArgumentException();

            graph.removeEdge(oldyh);

            graph.addEdge(Edges.directedEdge(y, h));

//            triplesAdded.add(new Triple(x, h, y));
//            impliedColliders.remove(new NodePair(x, h));
//            impliedColliders.remove(new NodePair(y, h));

            if (verbose) {
                TetradLogger.getInstance().log("directedEdges", "--- Directing " + oldyh + " to " +
                        graph.getEdge(y, h));
                out.println("--- Directing " + oldyh + " to " + graph.getEdge(y, h));
            }

            Edge oldxh = graph.getEdge(x, h);

            if (Edges.isUndirectedEdge(oldxh)) {
                graph.removeEdge(oldxh);

                graph.addEdge(Edges.directedEdge(x, h));

//                triplesAdded.add(new Triple(x, h, y));
//                impliedColliders.remove(new NodePair(x, h));
//                impliedColliders.remove(new NodePair(y, h));

                if (verbose) {
                    TetradLogger.getInstance().log("directedEdges", "--- Directing " + oldxh + " to " +
                            graph.getEdge(x, h));
                    out.println("--- Directing " + oldxh + " to " + graph.getEdge(x, h));
                }
            }
//            }
        }
    }

    private Set<Node> getCommonAdjacents(Node x, Node y) {
        Set<Node> commonAdjacents = new HashSet<>(graph.getAdjacentNodes(x));
        commonAdjacents.retainAll(graph.getAdjacentNodes(y));
        return commonAdjacents;
    }

    // Test if the candidate insertion is a valid operation
    // (Theorem 15 from Chickering, 2002).
    private boolean validInsert(Node x, Node y, Set<Node> s, Set<Node> naYX) {
        Set<Node> union = new HashSet<>(s);
        union.addAll(naYX);
        if (!isClique(union)) return false;
        if (existsUnblockedSemiDirectedPath(y, x, union, cycleBound)) {
//            System.out.println("Semidirected path from " + y + " to " + x);
            return false;
        }
        return true;

    }

    // Returns true if all of the members of 'union' are neighbors of y.
    private boolean allNeighbors(Node y, Set<Node> union) {
        for (Node n : union) {
            Edge e = graph.getEdge(y, n);
            if (e == null) {
                return false;
            }
            if (!Edges.isUndirectedEdge(e)) {
                return false;
            }
        }

        return true;
    }

    // Adds edges required by knowledge.
    private void addRequiredEdges(Graph graph) {
        if (!existsKnowledge()) return;

        for (Iterator<KnowledgeEdge> it = getKnowledge().requiredEdgesIterator(); it.hasNext(); ) {
            KnowledgeEdge next = it.next();

            Node nodeA = graph.getNode(next.getFrom());
            Node nodeB = graph.getNode(next.getTo());

            if (!graph.isAncestorOf(nodeB, nodeA)) {
                graph.removeEdges(nodeA, nodeB);
                graph.addDirectedEdge(nodeA, nodeB);
                TetradLogger.getInstance().log("insertedEdges", "Adding edge by knowledge: " + graph.getEdge(nodeA, nodeB));
            }
        }
        for (Edge edge : graph.getEdges()) {
            final String A = edge.getNode1().getName();
            final String B = edge.getNode2().getName();

            if (knowledge.isForbidden(A, B)) {
                Node nodeA = edge.getNode1();
                Node nodeB = edge.getNode2();
                if (nodeA == null || nodeB == null) throw new NullPointerException();

                if (graph.isAdjacentTo(nodeA, nodeB) && !graph.isChildOf(nodeA, nodeB)) {
                    if (!graph.isAncestorOf(nodeA, nodeB)) {
                        graph.removeEdges(nodeA, nodeB);
                        graph.addDirectedEdge(nodeB, nodeA);
                        TetradLogger.getInstance().log("insertedEdges", "Adding edge by knowledge: " + graph.getEdge(nodeB, nodeA));
                    }
                }

                if (!graph.isChildOf(nodeA, nodeB) && getKnowledge().isForbidden(nodeA.getName(), nodeB.getName())) {
                    if (!graph.isAncestorOf(nodeA, nodeB)) {
                        graph.removeEdges(nodeA, nodeB);
                        graph.addDirectedEdge(nodeB, nodeA);
                        TetradLogger.getInstance().log("insertedEdges", "Adding edge by knowledge: " + graph.getEdge(nodeB, nodeA));
                    }
                }
            } else if (knowledge.isForbidden(B, A)) {
                Node nodeA = edge.getNode2();
                Node nodeB = edge.getNode1();
                if (nodeA == null || nodeB == null) throw new NullPointerException();

                if (graph.isAdjacentTo(nodeA, nodeB) && !graph.isChildOf(nodeA, nodeB)) {
                    if (!graph.isAncestorOf(nodeA, nodeB)) {
                        graph.removeEdges(nodeA, nodeB);
                        graph.addDirectedEdge(nodeB, nodeA);
                        TetradLogger.getInstance().log("insertedEdges", "Adding edge by knowledge: " + graph.getEdge(nodeB, nodeA));
                    }
                }
                if (!graph.isChildOf(nodeA, nodeB) && getKnowledge().isForbidden(nodeA.getName(), nodeB.getName())) {
                    if (!graph.isAncestorOf(nodeA, nodeB)) {
                        graph.removeEdges(nodeA, nodeB);
                        graph.addDirectedEdge(nodeB, nodeA);
                        TetradLogger.getInstance().log("insertedEdges", "Adding edge by knowledge: " + graph.getEdge(nodeB, nodeA));
                    }
                }
            }
        }
    }

    // Use background knowledge to decide if an insert or delete operation does not orient edges in a forbidden
    // direction according to prior knowledge. If some orientation is forbidden in the subset, the whole subset is
    // forbidden.
    private boolean validSetByKnowledge(Node y, Set<Node> subset) {
        for (Node node : subset) {
            if (getKnowledge().isForbidden(node.getName(), y.getName())) {
                return false;
            }
        }
        return true;
    }

    // Find all adj that are connected to Y by an undirected edge that are adjacent to X (that is, by undirected or
    // directed edge).
    private synchronized Set<Node> getNaYX(Node x, Node y) {
//        if (true) return getNaYX2(x, y);

        List<Node> adj = graph.getAdjacentNodes(y);
        Set<Node> nayx = new HashSet<>();

        for (Node z : adj) {
            if (z == x) continue;
            Edge ez = graph.getEdge(z, y);
            if (!Edges.isUndirectedEdge(ez)) continue;
            if (!graph.isAdjacentTo(z, x)) continue;
            nayx.add(z);
        }

        return nayx;
    }

//    private synchronized Set<Node> getNaYX2(Node x, Node y) {
////        if (true) return getNaYX(x, y);
//
//        List<Edge> yEdges = graph.getEdges(y);
//        Set<Node> nayx = new HashSet<>();
//
//        for (Edge edge : yEdges) {
//            if ((Edges.isUndirectedEdge(edge)) || !edge.pointsTowards(y)) {
//                Node z = edge.getDistalNode(y);
//
//                Edge edge2 = graph.getEdge(z, x);
//
//                if (edge2 != null && ((Edges.isUndirectedEdge(edge2)) || !edge2.pointsTowards(x))) {
//                    nayx.add(z);
//                }
//
////                if (!graph.isAdjacentTo(z, x)) {
////                    continue;
////                }
////
////                nayx.add(z);
//            }
//        }
//
////        System.out.println("NaYX2 for " + x + "-->" + y + " = " + nayx);
//
//        return nayx;
//    }

    // Returns true iif the given set forms a clique in the given graph.
    private boolean isClique(Set<Node> nodes) {
        List<Node> _nodes = new ArrayList<>(nodes);
        for (int i = 0; i < _nodes.size() - 1; i++) {
            for (int j = i + 1; j < _nodes.size(); j++) {
                if (!graph.isAdjacentTo(_nodes.get(i), _nodes.get(j))) {
                    return false;
                }
            }
        }

        return true;
    }

    // Returns true if a path consisting of undirected and directed edges toward 'to' exists of
    // length at most 'bound'. Cycle checker in other words.
    private boolean existsUnblockedSemiDirectedPath(Node from, Node to, Set<Node> cond, int bound) {
        synchronized (graph) {
            Queue<Node> Q = new LinkedList<>();
            Set<Node> V = new HashSet<>();
            Q.offer(from);
            V.add(from);
            Node e = null;
            int distance = 0;

            while (!Q.isEmpty()) {
                Node t = Q.remove();
                if (t == to) return true;

                if (e == t) {
                    e = null;
                    distance++;
                    if (distance > (bound == -1 ? 1000 : bound)) return true;
                }

                for (Node u : graph.getAdjacentNodes(t)) {
                    Edge edge = graph.getEdge(t, u);
                    Node c = traverseSemiDirected(t, edge);
                    if (c == null) continue;
                    if (cond.contains(c)) continue;
                    if (c == to) return true;

                    if (!V.contains(c)) {
                        V.add(c);
                        Q.offer(c);

                        if (e == null) {
                            e = u;
                        }
                    }
                }
            }
        }

        return false;
    }

    // Used to find semidirected paths for cycle checking.
    private static Node traverseSemiDirected(Node node, Edge edge) {
        if (node == edge.getNode1()) {
            if (edge.getEndpoint1() == Endpoint.TAIL) {
                return edge.getNode2();
            }
        } else if (node == edge.getNode2()) {
            if (edge.getEndpoint2() == Endpoint.TAIL) {
                return edge.getNode1();
            }
        }
        return null;
    }

    // Runs the Meek rules on just the changed adj.
    private Set<Node> rebuildPatternRestricted(Node x, Node y) {
        Set<Node> visited = new HashSet<>();

        Set<Node> toProcess = new HashSet<>();
        toProcess.add(x);
        toProcess.add(y);
        toProcess.addAll(graph.getAdjacentNodes(x));
        toProcess.addAll(graph.getAdjacentNodes(y));

//        if (impliedColliders != null) {
//            for (NodePair triple : impliedColliders) {
//                boolean removed = graph.removeEdge(triple.getFirst(), triple.getSecond());
//                if (!removed) continue;
//                graph.addUndirectedEdge(triple.getFirst(), triple.getSecond());
//            }
//        }

        for (Node node : toProcess) {
            SearchGraphUtils.basicPatternRestricted2(node, graph);
//            SearchGraphUtils.basicPatternRestricted3(node, graph, triplesAdded);
//            SearchGraphUtils.basicPatternRestricted4(node, graph, triplesAdded);
        }

        for (Node node : toProcess) {
            visited.addAll(reorientNode(node));
        }

        if (TetradLogger.getInstance().isEventActive("rebuiltPatterns")) {
            TetradLogger.getInstance().log("rebuiltPatterns", "Rebuilt pattern = " + graph);
        }

        return visited;
    }

    // Runs Meek rules on just the changed adj.
    private Set<Node> reorientNode(Node a) {
        addRequiredEdges(graph);

        List<Node> nodes = new ArrayList<>();
        nodes.add(a);

        return meekOrientRestricted(nodes, getKnowledge());
    }

    // Runs Meek rules on just the changed adj.
    private Set<Node> meekOrientRestricted(List<Node> nodes, IKnowledge knowledge) {
        MeekRules rules = new MeekRules();
        rules.setKnowledge(knowledge);
        rules.orientImplied(graph, nodes);
//        this.impliedColliders.addAll(rules.getImpliedColliders());
        return rules.getVisited();
    }

    // Maps adj to their indices for quick lookup.
    private void buildIndexing(List<Node> nodes) {
        this.hashIndices = new ConcurrentSkipListMap<>();
        for (Node node : nodes) {
            this.hashIndices.put(node, variables.indexOf(node));
        }
    }

    // Removes information associated with an edge x->y.
    private synchronized void clearArrow(Node x, Node y) {
//        if (true) return;
        final OrderedPair<Node> pair = new OrderedPair<>(x, y);
        final Set<Arrow> lookupArrows = this.lookupArrows.get(pair);

        if (lookupArrows != null) {
            sortedArrows.removeAll(lookupArrows);
        }

        this.lookupArrows.remove(pair);
    }

    // Adds the given arrow for the adjacency i->j. These all are for i->j but may have
    // different T or H or NaYX sets, and so different bumps.
    private void addLookupArrow(Node i, Node j, Arrow arrow) {
        OrderedPair<Node> pair = new OrderedPair<>(i, j);
        Set<Arrow> arrows = lookupArrows.get(pair);

        if (arrows == null) {
            arrows = new ConcurrentSkipListSet<>();
            lookupArrows.put(pair, arrows);
        }

        arrows.add(arrow);
    }

    //===========================SCORING METHODS===================//

    /**
     * Scores the given DAG, up to a constant.
     */
    public double scoreDag(Graph dag) {
        buildIndexing(dag.getNodes());

        double score = 0.0;

        for (Node y : dag.getNodes()) {
            Set<Node> parents = new HashSet<>(dag.getParents(y));
            int index = hashIndices.get(y);
            int parentIndices[] = new int[parents.size()];
            Iterator<Node> pi = parents.iterator();
            int count = 0;
            while (pi.hasNext()) {
                Node nextParent = pi.next();
                parentIndices[count++] = hashIndices.get(nextParent);
            }

            score += gesScore.localScore(index, parentIndices);
        }
        return score;
    }

    private double scoreGraphChange(Node y, Set<Node> parents,
                                    Node x, Map<Node, Integer> hashIndices) {
        int yIndex = hashIndices.get(y);

        if (parents.contains(x)) throw new IllegalArgumentException();

        int[] parentIndices = new int[parents.size()];

        int count = 0;
        for (Node parent : parents) {
            parentIndices[count++] = hashIndices.get(parent);
        }

        return gesScore.localScoreDiff(yIndex, parentIndices, hashIndices.get(x));
    }

    private List<Node> getVariables() {
        return variables;
    }

    // Stores the graph, if its score knocks out one of the top ones.
    private void storeGraph() {
        if (getNumPatternsToStore() > 0) {
            Graph graphCopy = new EdgeListGraphSingleConnections(graph);
            topGraphs.addLast(new ScoredGraph(graphCopy, score));
        }

        if (topGraphs.size() == getNumPatternsToStore() + 1) {
            topGraphs.removeFirst();
        }
    }
}






