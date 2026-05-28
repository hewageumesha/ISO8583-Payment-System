package com.example.payment_app;

import static com.example.payment_app.ISOUtils.addTPDUAndLength;
import static com.example.payment_app.ISOUtils.bytesToHex;

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

                // Initialize the response layout structure clone
                ISOMsg resp = (ISOMsg) req.clone();
                resp.setMTI("0210"); // Turn the Financial Request into a Response Frame

                // This was injecting an unexpected field into the byte layout, causing the shift!

                // --- BANKING HOST RULE ENGINE ---
                String responseCode = "00"; // Default to Approved

                String pan = req.getString(2);
                String amount = req.getString(4);
                String expiry = req.getString(14);
                String privateDataCvv = req.getString(48);

                // 1. Host Card Routing System Check
                if (!pan.startsWith("4") && !pan.startsWith("5")) {
                    responseCode = "05"; // Decline
                }
                // 2. Host Expiry Check
                else if (isCardExpired(expiry)) {
                    responseCode = "14"; // Decline: Expired Card status
                }
                // 3. Mock Insufficient Funds Rule (Triggered if amount ends in 99)
                else if (amount != null && amount.endsWith("99")) {
                    responseCode = "51"; // Decline: Insufficient Funds
                }
                // 4. Mock CVV Validation Check (Triggered if user entered 999)
                else if (privateDataCvv != null && privateDataCvv.contains("CVV=999")) {
                    responseCode = "05"; // Decline
                }

                // Inject response parameters back cleanly into the identical cloned template structure
                resp.set(39, responseCode);
                resp.set(37, "123456789012");
                resp.set(38, "AUTH12");

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

    /**
     * Checks if the YYMM expiry string is chronologically in the past.
     */
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