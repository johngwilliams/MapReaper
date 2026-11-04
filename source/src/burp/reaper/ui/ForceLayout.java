package burp.reaper.ui;

/*
 * MapReaper part of the *Reaper Toolkit, created by John Gwilliams.
 *
 * A small force-directed (spring-electrical) layout. Repulsion between every pair of nodes, Hooke
 * attraction along edges, and a gentle pull toward the centre so disconnected components do not drift
 * off-screen. Runs one integration step per animation frame on the EDT. O(n^2) in the node count,
 * which is fine for the typical dependency graph; above {@link #MAX_PHYSICS_NODES} repulsion is
 * skipped so the UI stays responsive on pathological inputs.
 */

import burp.reaper.model.GraphModel;

import java.util.Collection;
import java.util.List;

final class ForceLayout {

    static final int MAX_PHYSICS_NODES = 900;

    private double repulsion = 11000.0;
    private double springLength = 95.0;
    private double springK = 0.015;
    private double gravity = 0.02;
    private double damping = 0.85;
    private double maxVelocity = 28.0;

    /**
     * Advance the simulation by one step around the given centre.
     *
     * @param fixed a node currently held by the user (dragged); never integrated. May be null.
     * @return the total kinetic energy, so the caller can stop animating once it settles.
     */
    double step(Collection<GraphModel.Node> nodes, List<GraphModel.Edge> edges,
                double centreX, double centreY, GraphModel.Node fixed) {
        int n = nodes.size();
        if (n == 0) {
            return 0;
        }
        boolean doRepulsion = n <= MAX_PHYSICS_NODES;

        // Position-based relaxation: each frame we recompute a displacement from scratch (no stored
        // momentum), which is simple and numerically well-behaved for a graph of this size.
        double[] fxArr = scratchX(n);
        double[] fyArr = scratchY(n);
        int i = 0;
        GraphModel.Node[] arr = nodes.toArray(new GraphModel.Node[0]);
        n = arr.length;

        // Repulsion (Coulomb).
        if (doRepulsion) {
            for (i = 0; i < n; i++) {
                GraphModel.Node a = arr[i];
                for (int j = i + 1; j < n; j++) {
                    GraphModel.Node b = arr[j];
                    double dx = a.x - b.x;
                    double dy = a.y - b.y;
                    double d2 = dx * dx + dy * dy;
                    if (d2 < 0.01) {
                        // coincident: nudge deterministically apart
                        dx = (i - j);
                        dy = (i + j) % 7 - 3;
                        d2 = dx * dx + dy * dy + 0.01;
                    }
                    double force = repulsion / d2;
                    double d = Math.sqrt(d2);
                    double ux = dx / d;
                    double uy = dy / d;
                    fxArr[i] += ux * force;
                    fyArr[i] += uy * force;
                    fxArr[j] -= ux * force;
                    fyArr[j] -= uy * force;
                }
            }
        }

        // Attraction (Hooke) along edges.
        java.util.HashMap<String, Integer> index = new java.util.HashMap<>();
        for (i = 0; i < n; i++) {
            index.put(arr[i].id, i);
        }
        for (GraphModel.Edge e : edges) {
            Integer si = index.get(e.src);
            Integer di = index.get(e.dst);
            if (si == null || di == null) {
                continue;
            }
            GraphModel.Node a = arr[si];
            GraphModel.Node b = arr[di];
            double dx = b.x - a.x;
            double dy = b.y - a.y;
            double d = Math.sqrt(dx * dx + dy * dy) + 0.0001;
            double force = springK * (d - springLength);
            double ux = dx / d;
            double uy = dy / d;
            fxArr[si] += ux * force;
            fyArr[si] += uy * force;
            fxArr[di] -= ux * force;
            fyArr[di] -= uy * force;
        }

        // Gravity toward centre + integrate.
        double energy = 0;
        for (i = 0; i < n; i++) {
            GraphModel.Node node = arr[i];
            if (node == fixed || node.pinned) {
                continue;
            }
            fxArr[i] += (centreX - node.x) * gravity;
            fyArr[i] += (centreY - node.y) * gravity;

            double vx = fxArr[i] * damping;
            double vy = fyArr[i] * damping;
            double v = Math.sqrt(vx * vx + vy * vy);
            if (v > maxVelocity) {
                vx = vx / v * maxVelocity;
                vy = vy / v * maxVelocity;
            }
            node.x += vx;
            node.y += vy;
            energy += vx * vx + vy * vy;
        }
        return energy;
    }

    // Reusable scratch buffers keyed by size, to avoid per-frame allocation churn.
    private double[] sx = new double[0];
    private double[] sy = new double[0];

    private double[] scratchX(int n) {
        if (sx.length < n) {
            sx = new double[n];
        } else {
            java.util.Arrays.fill(sx, 0, n, 0.0);
        }
        return sx;
    }

    private double[] scratchY(int n) {
        if (sy.length < n) {
            sy = new double[n];
        } else {
            java.util.Arrays.fill(sy, 0, n, 0.0);
        }
        return sy;
    }
}
