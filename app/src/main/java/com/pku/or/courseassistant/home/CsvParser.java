package com.pku.or.courseassistant.home;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

public class CsvParser {
    public List<String[]> parse(InputStream is) throws Exception {
        List<String[]> out = new ArrayList<>();
        // Read all bytes first so we can attempt multiple decodings (UTF-8 then GB18030)
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = is.read(buf)) != -1) bout.write(buf, 0, r);
        byte[] bytes = bout.toByteArray();

        String text = null;
        try {
            text = new String(bytes, "UTF-8");
        } catch (Exception ex) {
            // fallback
            text = new String(bytes, "GB18030");
        }
        // If UTF-8 produced replacement characters, try GB18030 (common for Excel CSVs)
        if (text.indexOf('\uFFFD') != -1) {
            text = new String(bytes, "GB18030");
        }

        BufferedReader br = new BufferedReader(new StringReader(text));
        String line;
        // Remove UTF-8 BOM if present in first line
        boolean first = true;
        while ((line = br.readLine()) != null) {
            if (first) {
                first = false;
                if (line.length() > 0 && line.charAt(0) == '\uFEFF') line = line.substring(1);
            }
            // simple split by comma, not handling quotes for MVP
            String[] cols = line.split(",");
            out.add(cols);
        }
        return out;
    }
}
