package com.pku.or.courseassistant.home;

import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Minimal XLSX parser: reads /xl/sharedStrings.xml and /xl/worksheets/sheet*.xml
 * Produces sheets with rows (array of String)
 */
public class XlsxLightParser {
    public static class Sheet {
        public String name;
        public List<String[]> rows = new ArrayList<>();
    }

    public static class ParseResult {
        public List<Sheet> sheets = new ArrayList<>();
    }

    public ParseResult parse(InputStream is) throws Exception {
        ZipInputStream zis = new ZipInputStream(is);
        Map<Integer, String> shared = new HashMap<>();
        Map<String, byte[]> sheetsXml = new HashMap<>();
        byte[] workbookXml = null;
        byte[] workbookRels = null;

        ZipEntry e;
        while ((e = zis.getNextEntry()) != null) {
            String name = e.getName();
            java.io.ByteArrayOutputStream bout = new java.io.ByteArrayOutputStream();
            int b;
            byte[] buf = new byte[8192];
            while ((b = zis.read(buf)) != -1) bout.write(buf, 0, b);
            byte[] data = bout.toByteArray();
            if (name.equals("xl/sharedStrings.xml")) {
                // parse sharedStrings
                XmlPullParserFactory f = XmlPullParserFactory.newInstance();
                XmlPullParser xp = f.newPullParser();
                xp.setInput(new InputStreamReader(new java.io.ByteArrayInputStream(data), "UTF-8"));
                int idx = 0;
                int eventType = xp.getEventType();
                StringBuilder sbStr = null;
                boolean inSi = false;
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        String n = xp.getName();
                        if ("si".equals(n)) {
                            inSi = true;
                            sbStr = new StringBuilder();
                        }
                        // when encountering a text node inside si, xp.next() may move to TEXT
                    } else if (eventType == XmlPullParser.TEXT) {
                        if (inSi && sbStr != null) {
                            sbStr.append(xp.getText());
                        }
                    } else if (eventType == XmlPullParser.END_TAG) {
                        if ("si".equals(xp.getName())) {
                            String cur = sbStr != null ? sbStr.toString() : "";
                            shared.put(idx++, cur);
                            inSi = false;
                            sbStr = null;
                        }
                    }
                    eventType = xp.next();
                }
            } else if (name.startsWith("xl/worksheets/sheet")) {
                sheetsXml.put(name, data);
            } else if (name.equals("xl/workbook.xml")) {
                workbookXml = data;
            } else if (name.equals("xl/_rels/workbook.xml.rels")) {
                workbookRels = data;
            }
        }
        // parse each sheet xml stored
        ParseResult res = new ParseResult();

        // build mapping from sheet path -> display name using workbook.xml and workbook.xml.rels
        Map<String, String> relIdToTarget = new HashMap<>();
        Map<String, String> pathToDisplayName = new HashMap<>();
        try {
            if (workbookRels != null) {
                XmlPullParserFactory f = XmlPullParserFactory.newInstance();
                XmlPullParser xp = f.newPullParser();
                xp.setInput(new InputStreamReader(new java.io.ByteArrayInputStream(workbookRels), "UTF-8"));
                int et = xp.getEventType();
                while (et != XmlPullParser.END_DOCUMENT) {
                    if (et == XmlPullParser.START_TAG) {
                        String n = xp.getName();
                        if ("Relationship".equals(n) || "relationship".equals(n)) {
                            String id = xp.getAttributeValue(null, "Id");
                            if (id == null) id = xp.getAttributeValue(null, "Id");
                            String target = xp.getAttributeValue(null, "Target");
                            if (id != null && target != null) {
                                // normalize target to xl/ path
                                String t = target.startsWith("xl/") ? target : (target.startsWith("/") ? target.substring(1) : "xl/" + target);
                                relIdToTarget.put(id, t);
                                try { } catch (Throwable _t) {}
                            }
                        }
                    }
                    et = xp.next();
                }
            }
            if (workbookXml != null) {
                XmlPullParserFactory f2 = XmlPullParserFactory.newInstance();
                XmlPullParser xp2 = f2.newPullParser();
                xp2.setInput(new InputStreamReader(new java.io.ByteArrayInputStream(workbookXml), "UTF-8"));
                int et = xp2.getEventType();
                while (et != XmlPullParser.END_DOCUMENT) {
                    if (et == XmlPullParser.START_TAG) {
                        String n = xp2.getName();
                        if ("sheet".equals(n) || "sheet".equalsIgnoreCase(n)) {
                            // sheet element has attributes: name and r:id (possibly namespaced)
                            String nameAttr = null;
                            String rid = null;
                            for (int i = 0; i < xp2.getAttributeCount(); i++) {
                                String an = xp2.getAttributeName(i);
                                String av = xp2.getAttributeValue(i);
                                if ("name".equals(an)) nameAttr = av;
                                if (an != null && (an.equals("r:id") || an.endsWith(":id") || an.equals("id"))) rid = av;
                            }
                            if (rid != null) {
                                String target = relIdToTarget.get(rid);
                                    if (target != null && nameAttr != null) {
                                    // map the sheet xml path (e.g. xl/worksheets/sheet1.xml) to the display name
                                    pathToDisplayName.put(target, nameAttr);
                                }
                            }
                        }
                    }
                    et = xp2.next();
                }
            }
        } catch (Exception ex) {
            // non-fatal: if mapping fails we'll fallback to path-based names
        }
        for (Map.Entry<String, byte[]> en : sheetsXml.entrySet()) {
            byte[] data = en.getValue();
            XmlPullParserFactory f = XmlPullParserFactory.newInstance();
            XmlPullParser xp = f.newPullParser();
            xp.setInput(new InputStreamReader(new java.io.ByteArrayInputStream(data), "UTF-8"));
            Sheet s = new Sheet();
            // prefer workbook-provided display name if available
            String key = en.getKey(); // e.g. xl/worksheets/sheet1.xml
            String display = pathToDisplayName.get(key);
            if (display != null) s.name = display; else s.name = key;
            try { } catch (Throwable _t) {}
            List<String> row = new ArrayList<>();
            int eventType = xp.getEventType();
            String curCellType = null;
            String curCellRef = null;
            String curText = null;
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    String n = xp.getName();
                    if (n.equals("c")) {
                        curCellType = xp.getAttributeValue(null, "t");
                        curCellRef = xp.getAttributeValue(null, "r");
                        curText = null;
                    } else if (n.equals("v") || n.equals("t")) {
                        xp.next();
                        if (xp.getEventType() == XmlPullParser.TEXT) {
                            curText = xp.getText();
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    String n = xp.getName();
                    if (n.equals("c")) {
                        String val = curText;
                        if (curCellType != null && curCellType.equals("s")) {
                            try {
                                int idx = Integer.parseInt(val);
                                val = shared.get(idx);
                            } catch (Exception ex) {}
                        }
                        row.add(val != null ? val : "");
                    } else if (n.equals("row")) {
                        s.rows.add(row.toArray(new String[0]));
                        row = new ArrayList<>();
                    }
                }
                eventType = xp.next();
            }
            res.sheets.add(s);
        }

        return res;
    }
}
