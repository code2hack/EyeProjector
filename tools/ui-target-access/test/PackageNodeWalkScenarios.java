package com.code2hack.eyeprojector.uitargetaccess;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Host algorithm checks with explicit doubles; these never establish Android UI access. */
public final class PackageNodeWalkScenarios {
    private static final class Node implements PackageNodeWalk.Node {
        final String name;
        final String pkg;
        final List<Node> children = new ArrayList<>();
        int closes;
        int childReads;
        Node(String name, String pkg, Node... children) {
            this.name = name; this.pkg = pkg; this.children.addAll(Arrays.asList(children));
        }
        public String packageName() { return pkg; }
        public int childCount() { return children.size(); }
        public PackageNodeWalk.Node child(int index) { childReads++; return children.get(index); }
        public void close() { closes++; }
    }

    private static void check(boolean value) {
        if (!value) throw new AssertionError();
    }

    public static void main(String[] args) throws Exception {
        Node unread = new Node("unread", "selected");
        Node excluded = new Node("other package", "other", unread);
        Node child = new Node("control", "selected");
        Node root = new Node("root", "selected", excluded, null, child);
        List<String> visited = new ArrayList<>();
        PackageNodeWalk.Counts counts = PackageNodeWalk.collect(root, "selected",
                (node, ordinal) -> visited.add(((Node) node).name + ordinal));
        check(visited.equals(List.of("root1", "control2")));
        check(counts.selectedNodes == 2 && counts.excludedPackageRoots == 1 && counts.nullChildren == 1);
        check(root.closes == 1 && child.closes == 1 && excluded.closes == 1);
        check(unread.closes == 0 && excluded.childReads == 0);

        Node nullPackage = new Node("null package", null, new Node("unread", "selected"));
        PackageNodeWalk.Counts noMatch = PackageNodeWalk.collect(nullPackage, "selected",
                (node, ordinal) -> { throw new AssertionError("Must not visit another package"); });
        check(noMatch.selectedNodes == 0 && nullPackage.childReads == 0 && nullPackage.closes == 1);

        Node failedChild = new Node("fail", "selected");
        Node failedRoot = new Node("root", "selected", failedChild);
        boolean failed = false;
        try {
            PackageNodeWalk.collect(failedRoot, "selected", (node, ordinal) -> {
                if (node == failedChild) throw new Exception("visitor failed");
            });
        } catch (Exception expected) { failed = true; }
        check(failed && failedRoot.closes == 1 && failedChild.closes == 1);

        Node deep = new Node("leaf", "selected");
        for (int i = 0; i < 25000; i++) deep = new Node("parent", "selected", deep);
        PackageNodeWalk.Counts large = PackageNodeWalk.collect(deep, "selected", (node, ordinal) -> {});
        check(large.selectedNodes == 25001);
        System.out.println("PASS: package scoping, null child, exception release and deep iterative traversal (host doubles only)");
    }
}
