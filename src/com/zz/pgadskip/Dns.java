package com.zz.pgadskip;

/** 最小的 DNS 报文处理：取出查询的域名、造一个「域名不存在」的回答。 */
final class Dns {
    static String qname(byte[] q) {
        if (q.length < 17) return null;
        int qd = ((q[4] & 0xFF) << 8) | (q[5] & 0xFF);
        if (qd < 1) return null;
        StringBuilder sb = new StringBuilder();
        int i = 12;
        while (i < q.length) {
            int len = q[i] & 0xFF;
            if (len == 0) break;
            if ((len & 0xC0) != 0 || i + 1 + len > q.length) return null;
            if (sb.length() > 0) sb.append('.');
            for (int k = 0; k < len; k++) sb.append((char) (q[i + 1 + k] & 0xFF));
            i += 1 + len;
        }
        return sb.toString().toLowerCase();
    }

    /** 照着查询原样抄一份，标成「回答 + 域名不存在（NXDOMAIN）」，不带任何记录。 */
    static byte[] nxdomain(byte[] q) {
        int end = 12;
        while (end < q.length && q[end] != 0) end += 1 + (q[end] & 0xFF);
        end += 1 + 4;                       // 结尾的 0 + QTYPE + QCLASS
        if (end > q.length) end = q.length;
        byte[] r = new byte[end];
        System.arraycopy(q, 0, r, 0, end);
        r[2] = (byte) (0x80 | (q[2] & 0x01)); // QR=1，保留 RD
        r[3] = (byte) 0x83;                  // RA=1，RCODE=3
        r[4] = 0; r[5] = 1;                  // QDCOUNT=1
        r[6] = r[7] = r[8] = r[9] = r[10] = r[11] = 0;
        return r;
    }
}
