package org.pegdown;

import org.parboiled.common.StringUtils;
import org.pegdown.ast.VerbatimNode;

public class DefaultVerbatimSerializer implements VerbatimSerializer {
    public static final DefaultVerbatimSerializer INSTANCE = new DefaultVerbatimSerializer();

    @Override
    public void serialize(final VerbatimNode node, final Printer printer) {
        Attributes attributes = new Attributes();

        if (!StringUtils.isEmpty(node.getType())) {
            attributes.add("class", node.getType());
        }

        printer.println().print("<pre><code").print(printer.preview(node, "code", attributes, false)).print('>');
        String text = node.getText();
        // print HTML breaks for all initial newlines
        while (!text.isEmpty() && text.charAt(0) == '\n') {
            printer.print("<br/>");
            text = text.substring(1);
        }
        printer.printEncoded(text);
        printer.print("</code></pre>");

    }
}
