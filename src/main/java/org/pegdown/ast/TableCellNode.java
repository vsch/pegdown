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

public class TableCellNode extends SuperNode {
    private int colSpan;

    public TableCellNode() {
    }

    public TableCellNode(List<Node> children, int colSpan) {
        super(children);
        this.colSpan = colSpan;
    }

    public int getColSpan() {
        return colSpan;
    }

    public boolean setColSpan(int colSpan) {
        this.colSpan = colSpan;
        return true;
    }

    @Override
    public void accept(Visitor visitor) {
        visitor.visit(this);
    }
}
