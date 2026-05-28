package com.example.payment_app;

import static com.example.payment_app.ISOUtils.addTPDUAndLength;

import android.util.Log;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.packager.ISO87BPackager;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Calendar;

public class SwitchServer extends Thread {

    private ServerSocket serverSocket;
    private boolean isRunning = true;

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(5000);
            Log.d("SWITCH_ENGINE", "Host Simulation Listening on Port 5000");

            while (isRunning) {
                Socket socket = serverSocket.accept();

                byte[] buffer = new byte[2048];
                int len = socket.getInputStream().read(buffer);
                if (len == -1) continue;

                byte[] isoPayload = Arrays.copyOfRange(buffer, 7, len);

                ISO87BPackager packager = new ISO87BPackager();
                ISOMsg req = new ISOMsg();
                req.setPackager(packager);
                req.unpack(isoPayload);

                ISOMsg resp = (ISOMsg) req.clone();
                String mti = req.getMTI();
                String responseCode = "00"; // Default Approved

                if ("0200".equals(mti)) {
                    // --- FINANCIAL REQUEST PROCESSOR ---
                    resp.setMTI("0210");
                    String pan = req.getString(2);
                    String amount = req.getString(4);
                    String expiry = req.getString(14);
                    String privateDataCvv = req.getString(48);

                    if (!pan.startsWith("4") && !pan.startsWith("5")) {
                        responseCode = "05";
                    } else if (isCardExpired(expiry)) {
                        responseCode = "14";
                    } else if (amount != null && amount.endsWith("99")) {
                        responseCode = "51";
                    } else if (privateDataCvv != null && privateDataCvv.contains("CVV=999")) {
                        responseCode = "05";
                    }

                    resp.set(37, "123456789012");
                    resp.set(38, "AUTH12");

                } else if ("0500".equals(mti)) {
                    // --- RECONCILIATION INITIALIZER PROCESSOR ---
                    resp.setMTI("0510");
                    Log.d("SWITCH_ENGINE", "Settlement Init Intercepted! Metadata Breakdown: " + req.getString(48));
                    responseCode = "00";

                } else if ("0320".equals(mti)) {
                    // --- DETAILED BATCH RECORD UPLOAD PROCESSOR ---
                    resp.setMTI("0330");
                    Log.d("SWITCH_ENGINE", "Stored Upload Detail Entry -> PAN: " + req.getString(2) + " | AMT: " + req.getString(4));
                    responseCode = "00";
                }

                resp.set(39, responseCode);
                byte[] packedResponse = resp.pack();
                byte[] framedResponseBytes = addTPDUAndLength(packedResponse);

                socket.getOutputStream().write(framedResponseBytes);
                socket.getOutputStream().flush();
                socket.close();
            }
        } catch (Exception e) {
            Log.e("SWITCH_ENGINE", "Core host parsing engine fault error", e);
        }
    }

    private boolean isCardExpired(String expiryYYMM) {
        if (expiryYYMM == null || expiryYYMM.length() != 4) return true;
        try {
            int inputYear = Integer.parseInt(expiryYYMM.substring(0, 2)) + 2000;
            int inputMonth = Integer.parseInt(expiryYYMM.substring(2, 4));

            Calendar current = Calendar.getInstance();
            int currentYear = current.get(Calendar.YEAR);
            int currentMonth = current.get(Calendar.MONTH) + 1;

            if (inputYear < currentYear) {
                return true;
            } else if (inputYear == currentYear && inputMonth < currentMonth) {
                return true;
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    public void shutdown() {
        try {
            isRunning = false;
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception ignored) {}
    }
}