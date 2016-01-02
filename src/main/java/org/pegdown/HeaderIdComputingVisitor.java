/*
 * Copyright (C) 2010-2011 Mathias Doenitz
 *
 * Based on peg-markdown (C) 2008-2010 John MacFarlane
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.pegdown;

import org.pegdown.ast.*;

import java.util.*;

public class HeaderIdComputingVisitor implements Visitor {
    public final Map<Integer, String> headerOffsetAnchorIds = new HashMap<Integer, String>();
    protected final HeaderIdComputer headerIdComputer;
    private StringBuilder childText = null;

    public HeaderIdComputingVisitor(HeaderIdComputer headerIdComputer) {
        this.headerIdComputer = headerIdComputer;
    }

    void visitNodeRecursively(Node node) {
        if (node instanceof SuperNode) {
            for (Node child : node.getChildren()) {
                child.accept(this);
            }
        } else if (childText != null && (node.getClass() == TextNode.class || (node.getClass() == SpecialTextNode.class && node.getEndIndex() - node.getStartIndex() <= 1))) {
            childText.append(((TextNode) node).getText());
        }
    }

    void visitNodeRecursively(Node node, StringBuilder childText) {
        StringBuilder prevChildText = this.childText;
        this.childText = childText;
        visitNodeRecursively(node);
        if (prevChildText != null) {
            prevChildText.append(childText);
        }

        this.childText = prevChildText;
    }

    @Override
    public void visit(HeaderNode node) {
        AnchorLinkNode anchorLinkNode = null;

        if (node.getChildren().size() > 0 && node.getChildren().get(0) instanceof AnchorLinkNode) {
            anchorLinkNode = (AnchorLinkNode) node.getChildren().get(0);
        }
        StringBuilder headerText = new StringBuilder();
        visitNodeRecursively(node, headerText);
        if (headerText.length() == 0 && anchorLinkNode != null) {
            // must me extlinks/wrap so all text is in the anchor
            headerText.append(anchorLinkNode.getText());
        }
        String anchorId = headerIdComputer.computeHeaderId(node, anchorLinkNode, headerText.toString());
        headerOffsetAnchorIds.put(node.getStartIndex(), anchorId);
    }

    @Override
    public void visit(AbbreviationNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(AnchorLinkNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(AutoLinkNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(BlockQuoteNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(BulletListNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(CodeNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(DefinitionListNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(DefinitionNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(DefinitionTermNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(FootnoteNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(FootnoteRefNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(ExpImageNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(ExpLinkNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(HtmlBlockNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(InlineHtmlNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(ListItemNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(MailLinkNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(OrderedListNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(ParaNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(QuotedNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(ReferenceNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(RefImageNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(RefLinkNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(RootNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(SimpleNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(SpecialTextNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(StrikeNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(StrongEmphSuperNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(TableBodyNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(TableCaptionNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(TableCellNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(TableColumnNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(TableHeaderNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(TableNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(TableRowNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(TocNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(VerbatimNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(WikiLinkNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(TextNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(SuperNode node) {
        visitNodeRecursively(node);
    }
    @Override
    public void visit(Node node) {
        visitNodeRecursively(node);
    }
}
