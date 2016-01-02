package org.pegdown;

import org.pegdown.ast.AnchorLinkNode;
import org.pegdown.ast.HeaderNode;

public interface HeaderIdComputer {
    String computeHeaderId(HeaderNode node, AnchorLinkNode anchorLinkNode, String headerText);
}
