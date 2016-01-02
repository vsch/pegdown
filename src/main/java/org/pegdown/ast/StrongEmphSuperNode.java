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

import java.util.List;

public class StrongEmphSuperNode extends SuperNode {

    private boolean isStrong = false;
    private boolean isClosed = false;
    private String chars;

    public StrongEmphSuperNode(List<Node> children) {
        super(children);
    }

    public StrongEmphSuperNode(String str) {
        this.isStrong = (str.length() == 2); // ** and __ are strong, * and _ emph
        this.chars = str;
    }
    public StrongEmphSuperNode(StrongEmphSuperNode node) {
        super(node.getChildren());
        this.isClosed = node.isClosed;
        this.chars = node.chars;
    }

    public StrongEmphSuperNode(List<Node> children, String chars, boolean isStrong, boolean isClosed) {
        super(children);
        this.chars = chars;
        this.isClosed = isClosed;
        this.isStrong = isStrong;
    }

    public boolean isStrong() {
        return isStrong;
    }

    public boolean isClosed() {
        return isClosed;
    }

    public void setClosed(boolean closed){
        this.isClosed = closed;
    }

    public String getChars() {
        return chars;
    }

    @Override
    public void accept(Visitor visitor) {
        visitor.visit(this);
    }
}
