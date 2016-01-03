package org.pegdown.ast;

import java.util.List;

public abstract class AbstractNode implements Node {
    private int startIndex;
    private int endIndex;

    public int getStartIndex() {
        return startIndex;
    }

    public int getEndIndex() {
        return endIndex;
    }

    public AbstractNode setRange(int startIndex, int endIndex) {
        this.startIndex = startIndex;
        this.endIndex = endIndex;
        return this;
    }

    public void setStartIndex(int startIndex) {
        this.startIndex = startIndex;
    }

    public void setEndIndex(int endIndex) {
        this.endIndex = endIndex;
    }

    public void shiftIndices(int delta) {
        startIndex += delta;
        endIndex += delta;

        shiftIndices(delta, getChildren());
    }

    public static void shiftIndices(int delta, List<? extends Node> nodes) {
        for (Node subNode : nodes) {
            ((AbstractNode) subNode).shiftIndices(delta);
        }
    }

    public static void mapIndices(int[] ixMap, List<? extends Node> nodes) {
        for (Node subNode : nodes) {
            ((AbstractNode) subNode).mapIndices(ixMap);
        }
    }

    public void mapIndices(int[] ixMap) {
        startIndex = ixMap[startIndex];
        endIndex = ixMap[endIndex];

        mapIndices(ixMap, getChildren());
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " [" + startIndex + '-' + endIndex + ']';
    }
}
