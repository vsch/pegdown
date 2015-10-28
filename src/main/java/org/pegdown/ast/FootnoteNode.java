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

package org.pegdown.ast;

public class FootnoteNode extends TextNode {
    private int number;
    private Node footnote;

    public FootnoteNode(String text) {
        super(text);
        this.number = Integer.parseInt(text.substring(2, text.indexOf(']')));
    }

    public int getNumber() {
        return number;
    }

    public Node getFootnote() {
        return footnote;
    }

    public boolean setFootnote(Node footnote) {
        this.footnote = footnote;
        setEndIndex(footnote.getEndIndex());
        return true;
    }

    @Override
    public void accept(Visitor visitor) {
        visitor.visit(this);
    }
}
