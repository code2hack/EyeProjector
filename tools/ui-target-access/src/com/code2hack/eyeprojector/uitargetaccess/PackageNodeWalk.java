package com.code2hack.eyeprojector.uitargetaccess;

import java.util.ArrayDeque;

/** Traversal only. No Android capability is inferred from this host-testable code. */
final class PackageNodeWalk {
    interface Node {
        String packageName();
        int childCount();
        Node child(int index);
        void close();
    }

    interface Visitor { void visit(Node node, int ordinal) throws Exception; }

    static final class Counts {
        int selectedNodes;
        int excludedPackageRoots;
        int nullChildren;
    }

    private static final class Frame {
        final Node node;
        int nextChild;
        int childCount;
        boolean entered;
        Frame(Node node) { this.node = node; }
    }

    /** Owns root and each returned child, including when the visitor/API throws. */
    static Counts collect(Node root, String targetPackage, Visitor visitor) throws Exception {
        Counts counts = new Counts();
        ArrayDeque<Frame> pending = new ArrayDeque<>();
        pending.push(new Frame(root));
        try {
            while (!pending.isEmpty()) {
                Frame frame = pending.peek();
                if (!frame.entered) {
                    frame.entered = true;
                    if (!targetPackage.equals(frame.node.packageName())) {
                        counts.excludedPackageRoots++;
                        pending.pop().node.close();
                        continue;
                    }
                    visitor.visit(frame.node, ++counts.selectedNodes);
                    frame.childCount = frame.node.childCount();
                }
                if (frame.nextChild == frame.childCount) {
                    pending.pop().node.close();
                } else {
                    Node child = frame.node.child(frame.nextChild++);
                    if (child == null) counts.nullChildren++;
                    else pending.push(new Frame(child));
                }
            }
            return counts;
        } finally {
            while (!pending.isEmpty()) pending.pop().node.close();
        }
    }
}
