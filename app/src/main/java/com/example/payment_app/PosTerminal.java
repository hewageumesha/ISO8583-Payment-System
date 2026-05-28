package com.example.payment_app;

import static com.example.payment_app.ISOUtils.addTPDUAndLength;
import static com.example.payment_app.ISOUtils.bytesToHex;
import android.util.Log;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.packager.ISO87BPackager;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

public class PosTerminal extends Thread {

    public interface TerminalResultListener {
        void onTransactionComplete(String responseCode, String rawPayload);
        void onTransactionFailed(String errorReason);
    }

    private final TerminalResultListener listener;
    private final String cardNo;
    private final String amount;
    private final String expiry;
    private final String cvv;

    public PosTerminal(String cardNo, String amount, String expiry, String cvv, TerminalResultListener listener) {
        this.cardNo = cardNo;
        this.amount = amount;
        this.expiry = expiry;
        this.cvv = cvv;
        this.listener = listener;
    }

    @Override
    public void run() {
        try {
            ISO87BPackager packager = new ISO87BPackager();
            ISOMsg isoMsg = new ISOMsg();
            isoMsg.setPackager(packager);

            // FIX: Pad the amount to exactly 12 digits (e.g., "80" becomes "000000008000")
            // This treats the input as cents/minor units dynamically.
            long amountVal = Long.parseLong(amount);
            String paddedAmount = String.format(Locale.US, "%012d", amountVal * 100);

            isoMsg.setMTI("0200");
            isoMsg.set(2, cardNo);
            isoMsg.set(3, "000000");
            isoMsg.set(4, paddedAmount); // Uses the safely padded 12-digit format

            String datetime = new SimpleDateFormat("MMddHHmmss", Locale.getDefault()).format(new Date());

            isoMsg.set(11, String.format(Locale.US, "%06d", new Random().nextInt(999999)));
            isoMsg.set(12, datetime.substring(4));
            isoMsg.set(13, datetime.substring(0, 4));
            isoMsg.set(14, expiry);
            isoMsg.set(18, "5999");
            isoMsg.set(22, "051");
            isoMsg.set(24, "001");
            isoMsg.set(25, "00");

            // Dynamically construct basic Track 2 structure to avoid hardcoded mismatches
            isoMsg.set(35, cardNo + "=" + expiry + "10100000");

            isoMsg.set(37, "123456789012");
            isoMsg.set(41, "TERMID01");
            isoMsg.set(42, "MERCHANT000001 ");
            isoMsg.set(49, "144"); // LKR Currency standard ISO code
            isoMsg.set(62, "INV001");
            isoMsg.set(48, "CVV=" + cvv);

            // Pack the structural object into binary representation
            byte[] binaryDataPayload = isoMsg.pack();
            byte[] completeNetworkMessageFrame = addTPDUAndLength(binaryDataPayload);

            // Establish TCP link to local server port
            Socket socket = new Socket("127.0.0.1", 5000);
            socket.setSoTimeout(5000); // Set a 5-second timeout safeguard so the UI won't hang forever

            socket.getOutputStream().write(completeNetworkMessageFrame);
            socket.getOutputStream().flush();

            byte[] respBuffer = new byte[2048];
            int len = socket.getInputStream().read(respBuffer);
            socket.close();

            if (len != -1) {
                // Strip length + TPDU (7 bytes total) before handing to unpacked ISO instance
                byte[] isolatedIsoDataPayload = Arrays.copyOfRange(respBuffer, 7, len);
                ISOMsg response = new ISOMsg();
                response.setPackager(packager);
                response.unpack(isolatedIsoDataPayload);

                String actionResponseCode = response.getString(39);
                if (listener != null) {
                    listener.onTransactionComplete(actionResponseCode, bytesToHex(respBuffer, len));
                }
            } else {
                if (listener != null) listener.onTransactionFailed("Empty network packet frame returned from host.");
            }
        } catch (Exception e) {
            if (listener != null) listener.onTransactionFailed("Execution Fault: " + e.getMessage());
        }
    }
}