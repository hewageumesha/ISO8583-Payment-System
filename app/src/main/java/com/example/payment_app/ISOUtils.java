package com.example.payment_app;

import java.io.ByteArrayOutputStream;

public class ISOUtils {

    public static byte[] addTPDUAndLength(byte[] iso) throws Exception {
        byte[] tpdu = hexStringToByteArray("6000150000");
        int totalLen = tpdu.length + iso.length;

        byte[] len = new byte[2];
        len[0] = (byte) (totalLen >> 8);
        len[1] = (byte) (totalLen);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(len);
        out.write(tpdu);
        out.write(iso);

        return out.toByteArray();
    }

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        if (len % 2 != 0) {
            throw new IllegalArgumentException("Invalid hex string length");
        }
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    public static String bytesToHex(byte[] bytes, int length) {
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < length; i++) {
            hex.append(String.format("%02X", bytes[i]));
        }
        return hex.toString();
    }
}
