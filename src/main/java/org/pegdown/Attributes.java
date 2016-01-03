package org.pegdown;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class Attributes {
    public final HashMap<String, String> attrMap = new HashMap<String, String>();
    public final ArrayList<String> attrOrder = new ArrayList<String>();

    public Attributes() {
    }

    public Attributes(String name, String value) {
        add(name, value);
    }

    public Attributes(List<LinkRenderer.Attribute> attributes) {
        addAll(attributes);
    }

    public Attributes add(String attribute, String value) {
        addDelimitedValue(attribute, " ", value);
        return this;
    }

    public Attributes addAll(List<LinkRenderer.Attribute> attributes) {
        for (LinkRenderer.Attribute attribute : attributes) {
            add(attribute.name, attribute.value);
        }
        return this;
    }

    public Attributes addDelimitedValue(String attribute, String delim, String value) {
        if (attrMap.containsKey(attribute)) {
            attrMap.put(attribute, attrMap.get(attribute) + delim + value);
        } else {
            attrMap.put(attribute, value);
            attrOrder.add(attribute);
        }
        return this;
    }

    public Attributes replace(String attribute, String value) {
        if (attrMap.containsKey(attribute)) {
            attrMap.put(attribute, value);
        } else {
            attrMap.put(attribute, value);
            attrOrder.add(attribute);
        }
        return this;
    }

    public Attributes removeDelimitedValue(String name, String delim, String value, boolean skipEmpties) {
        if (attrMap.containsKey(name)) {
            String[] classList = attrMap.get(name).split(delim);
            String classAttr = "";
            for (String classValue : classList) {
                if ((!skipEmpties || !classValue.isEmpty()) && !classValue.equals(value)) {
                    classAttr += delim + classValue;
                }
            }

            // vsch: we always put it back so as not to change the order of when it was defined, otherwise tests may fail
            attrMap.put(name, classAttr);
        }
        return this;
    }

    // vsch: use these to manipulate classes in preview()
    public Attributes removeClass(String value) {
        return removeDelimitedValue("class", " ", value, true);
    }

    public Attributes addClass(String value) {
        return add("class", value);
    }

    public Attributes replaceClass(String value) {
        return replace("class", value);
    }

    public boolean hasClass(String value) {
        String classAttr = " " + get("class", "") + " ";
        return classAttr.contains(" " + value + " ");
    }

    public boolean contains(String attribute) {
        return attrMap.containsKey(attribute);
    }

    public String get(String attribute, String valueIfMissing) {
        if (!attrMap.containsKey(attribute)) return valueIfMissing;
        return attrMap.get(attribute);
    }

    // vsch: NOTE: only lowercase "class" attribute is trimmed and skipped if it is empty, the rest are output as is, if you want something else take care of it in preview()
    void print(Printer printer) {
        for (String name : attrOrder) {
            assert attrMap.containsKey(name) : "Unexpected, key: " + name + " is not in attrMap, must have been manipulated outside of Attribute class";
            if (name.equals("class")) {
                String classAttr = attrMap.get(name).trim();
                if (!classAttr.isEmpty()) {
                    printer.print(' ').print(name).print('=').print('"').print(attrMap.get(name).replace("\\", "\\\\").replace("\"", "\\\"")).print('"');
                }
            } else if (name.equals("src") || name.equals("href")) {
                String url = attrMap.get(name).trim();
                int pos = url.indexOf('?');
                if (pos >= 0 && pos < url.length()) {
                    // url encode the query part
                    try {
                        String query = url.substring(pos + 1);
                        // reverse URL encoding of =, &
                        url = url.substring(0, pos + 1) + URLEncoder.encode(query, "UTF-8").replace("+","%20").replace("%3D", "=").replace("%26", "&amp;");
                    } catch (UnsupportedEncodingException e) {
                        e.printStackTrace();
                    }
                }
                printer.print(' ').print(name).print('=').print('"').print(url).print('"');
            } else {
                printer.print(' ').print(name).print('=').print('"').print(attrMap.get(name).replace("\\", "\\\\").replace("\"", "\\\"")).print('"');
            }
        }
    }
}
