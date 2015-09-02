/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fabric8.patch.installer;

import org.jdom.Element;
import org.jdom.Namespace;

import java.io.*;
import java.util.ArrayList;

public class Support {

    static public ArrayList<Element> findChildrenWithText(Element element, String tag, String text, Namespace NS) {
        ArrayList<Element> children = new ArrayList<Element>();
        for (Object o : element.getChildren(tag, NS)) {
            Element el = (Element) o;
            if( text.equals(el.getTextTrim()) ) {
                children.add(el);
            }
        }
        return children;
    }

    static public ArrayList<Element> findChildrenWith(Element element, String tag, String attr, String value, Namespace NS) {
        ArrayList<Element> children = new ArrayList<Element>();
        for (Object o : element.getChildren(tag, NS)) {
            Element el = (Element) o;
            if( value.equals(el.getAttributeValue(attr))) {
                children.add(el);
            }
        }
        return children;
    }


    static void pump(InputStream in, OutputStream out) throws IOException {
        int len;
        byte[] buffer = new byte[1024 * 4];
        while ((len = in.read(buffer)) > 0) {
            out.write(buffer, 0, len);
        }
    }

    static String readText(File jreFile) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try (FileInputStream is = new FileInputStream(jreFile)) {
            pump(is, os);
        }
        return new String(os.toByteArray(), "UTF-8");
    }

    static void writeText(File jreFile, String text) throws IOException {
        ByteArrayInputStream is = new ByteArrayInputStream(text.getBytes("UTF-8"));
        try (FileOutputStream os = new FileOutputStream(jreFile)) {
            pump(is, os);
        }
    }

}
