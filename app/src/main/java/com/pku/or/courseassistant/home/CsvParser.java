package com.pku.or.courseassistant.home;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV文件解析器
 * 支持UTF-8和GB18030编码自动检测
 * 处理UTF-8 BOM标记
 */
public class CsvParser {
    /**
     * 解析CSV文件为二维字符串数组
     * 
     * 特性：
     * - 自动尝试UTF-8编码，失败后回退到GB18030
     * - 检测并移除UTF-8 BOM（Byte Order Mark）
     * - 使用逗号分隔列（简化版，不处理引号内的逗号）
     * 
     * @param is 输入流
     * @return 二维数组，每行代表CSV的一行，每列代表一个单元格
     * @throws Exception 读取或解析错误
     */
    public List<String[]> parse(InputStream is) throws Exception {
        List<String[]> out = new ArrayList<>();
        
        // 首先读取所有字节，以便尝试多种编码
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = is.read(buf)) != -1) bout.write(buf, 0, r);
        byte[] bytes = bout.toByteArray();

        String text = null;
        try {
            // 优先尝试UTF-8编码
            text = new String(bytes, "UTF-8");
        } catch (Exception ex) {
            // 失败则使用GB18030（常见于Excel导出的CSV）
            text = new String(bytes, "GB18030");
        }
        
        // 如果UTF-8解码产生了替换字符（\uFFFD），说明编码不对，尝试GB18030
        if (text.indexOf('\uFFFD') != -1) {
            text = new String(bytes, "GB18030");
        }

        BufferedReader br = new BufferedReader(new StringReader(text));
        String line;
        
        // 移除第一行的UTF-8 BOM（如果存在）
        boolean first = true;
        while ((line = br.readLine()) != null) {
            if (first) {
                first = false;
                // BOM字符为\uFEFF
                if (line.length() > 0 && line.charAt(0) == '\uFEFF') line = line.substring(1);
            }
            
            // 简单按逗号分隔（MVP版本，不处理引号内的逗号）
            String[] cols = line.split(",");
            out.add(cols);
        }
        return out;
    }
}
