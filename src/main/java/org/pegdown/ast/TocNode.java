package org.pegdown.ast;

import java.util.List;

/**
 * org.pegdown.ast.TocNode
 *
 * @author chao
 * @version 1.0 - 2015-11-05
 */
public class TocNode extends SuperNode {

    private final List<HeaderNode> headers;
    private final int level;

    public TocNode(List<HeaderNode> headers, int level) {
        this.headers = headers;
        this.level = level;
    }

    public TocNode(List<Node> children, List<HeaderNode> headers, int level) {
        super(children);
        this.headers = headers;
        this.level = level;
    }

    public List<HeaderNode> getHeaders() {
        return headers;
    }

    public int getLevel() {
        return level;
    }

    @Override
    public void accept(Visitor visitor) {
        visitor.visit(this);
    }

    @Override
    public String toString() {
        return super.toString() + " TOC";
    }
}
