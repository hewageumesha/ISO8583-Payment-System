package com.example.payment_app;

import static com.example.payment_app.ISOUtils.addTPDUAndLength;
import static com.example.payment_app.ISOUtils.bytesToHex;

import android.content.Context;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.packager.ISO87BPackager;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class PosTerminal extends Thread {

    public interface TerminalResultListener {
        void onTransactionComplete(String responseCode, String rawPayload);
        void onTransactionFailed(String errorReason);
        void onSettlementLog(String log);
    }

    private final TerminalResultListener listener;
    private final String mode;
    private String cardNo, amount, expiry, cvv;
    private final DBHelper dbHelper;

    public PosTerminal(Context ctx, String cardNo, String amount, String expiry, String cvv, TerminalResultListener listener) {
        this.mode = "SALE";
        this.cardNo = cardNo;
        this.amount = amount;
        this.expiry = expiry;
        this.cvv = cvv;
        this.listener = listener;
        this.dbHelper = new DBHelper(ctx);
    }

    public PosTerminal(Context ctx, TerminalResultListener listener) {
        this.mode = "SETTLEMENT";
        this.listener = listener;
        this.dbHelper = new DBHelper(ctx);
    }

    @Override
    public void run() {
        try {
            ISO87BPackager packager = new ISO87BPackager();
            Socket socket;
            byte[] respBuffer = new byte[2048];
            int len;

            if ("SALE".equals(mode)) {
                // ================= FINANCIAL SALE PROCESSOR (0200) =================
                ISOMsg isoMsg = new ISOMsg();
                isoMsg.setPackager(packager);

                long amountVal = Long.parseLong(amount);
                String paddedAmount = String.format(Locale.US, "%012d", amountVal * 100);
                String randomStan = String.format(Locale.US, "%06d", new Random().nextInt(999999));

                isoMsg.setMTI("0200");
                isoMsg.set(2, cardNo);
                isoMsg.set(3, "000000");
                isoMsg.set(4, paddedAmount);

                String datetime = new SimpleDateFormat("MMddHHmmss", Locale.getDefault()).format(new Date());

                isoMsg.set(11, randomStan);
                isoMsg.set(12, datetime.substring(4));
                isoMsg.set(13, datetime.substring(0, 4));
                isoMsg.set(14, expiry);
                isoMsg.set(18, "5999");
                isoMsg.set(22, "051");
                isoMsg.set(24, "001");
                isoMsg.set(25, "00");
                isoMsg.set(35, cardNo + "=" + expiry + "10100000");
                isoMsg.set(37, "123456789012");
                isoMsg.set(41, "TERMID01");
                isoMsg.set(42, "MERCHANT000001 ");
                isoMsg.set(49, "144");
                isoMsg.set(62, "INV001");
                isoMsg.set(48, "CVV=" + cvv);

                byte[] completeNetworkMessageFrame = addTPDUAndLength(isoMsg.pack());

                socket = new Socket("127.0.0.1", 5000);
                socket.setSoTimeout(5000);

                socket.getOutputStream().write(completeNetworkMessageFrame);
                socket.getOutputStream().flush();

                len = socket.getInputStream().read(respBuffer);
                socket.close();

                if (len != -1) {
                    byte[] isolatedIsoDataPayload = Arrays.copyOfRange(respBuffer, 7, len);
                    ISOMsg response = new ISOMsg();
                    response.setPackager(packager);
                    response.unpack(isolatedIsoDataPayload);

                    String actionResponseCode = response.getString(39);

                    if ("00".equals(actionResponseCode)) {
                        dbHelper.insertTransaction(cardNo, paddedAmount, expiry, randomStan);
                    }

                    if (listener != null) {
                        listener.onTransactionComplete(
                                actionResponseCode,
                                bytesToHex(completeNetworkMessageFrame, completeNetworkMessageFrame.length) + "|" + bytesToHex(respBuffer, len)
                        );
                    }
                } else {
                    if (listener != null) listener.onTransactionFailed("Empty network packet frame returned from host.");
                }

            } else {
                // ================= BATCH SETTLEMENT CLEARING PIPELINE =================
                List<DBHelper.TxModel> txList = dbHelper.getAllTransactions();
                if (txList.isEmpty()) {
                    if (listener != null) listener.onTransactionFailed("Batch Empty! No approved sales found to settle.");
                    return;
                }

                long totalAmount = 0;
                for (DBHelper.TxModel tx : txList) {
                    totalAmount += Long.parseLong(tx.amount);
                }

                if (listener != null) listener.onSettlementLog("-> Transmitting MTI 0500 (Reconciliation Request)...");

                // Step A: Dispatch Reconciliation Header Handshake (MTI 0500)
                ISOMsg reconciliationMsg = new ISOMsg();
                reconciliationMsg.setPackager(packager);
                reconciliationMsg.setMTI("0500");
                reconciliationMsg.set(3, "920000");
                reconciliationMsg.set(11, String.format(Locale.US, "%06d", new Random().nextInt(999999)));
                reconciliationMsg.set(41, "TERMID01");
                reconciliationMsg.set(48, String.format(Locale.US, "CNT=%03d,AMT=%012d", txList.size(), totalAmount));

                byte[] reconciliationPackedBytes = addTPDUAndLength(reconciliationMsg.pack());
                // Save 0500 Request Hex before sending
                String saved0500HexPayload = bytesToHex(reconciliationPackedBytes, reconciliationPackedBytes.length);

                socket = new Socket("127.0.0.1", 5000);
                socket.getOutputStream().write(reconciliationPackedBytes);
                len = socket.getInputStream().read(respBuffer);
                socket.close();

                if (len == -1) {
                    if (listener != null) listener.onTransactionFailed("Settlement abandoned: Host communication line failed.");
                    return;
                }

                byte[] isolatedIsoDataPayload = Arrays.copyOfRange(respBuffer, 7, len);
                ISOMsg reconResponse = new ISOMsg();
                reconResponse.setPackager(packager);
                reconResponse.unpack(isolatedIsoDataPayload);

                String reconResponseCode = reconResponse.getString(39);
                // Save 0510 Response Hex
                String saved0510HexPayload = bytesToHex(respBuffer, len);

                if (!"00".equals(reconResponseCode)) {
                    if (listener != null) listener.onTransactionFailed("Settlement Refused by Host. Code: " + reconResponseCode);
                    return;
                }

                // Step B: Sequentially stream log entries across independent sockets (MTI 0320)
                if (listener != null) listener.onSettlementLog("-> Reconciliation Confirmed (00).\n-> Streaming Batch Elements (MTI 0320)...");

                for (int i = 0; i < txList.size(); i++) {
                    DBHelper.TxModel tx = txList.get(i);
                    ISOMsg uploadMsg = new ISOMsg();
                    uploadMsg.setPackager(packager);
                    uploadMsg.setMTI("0320");
                    uploadMsg.set(2, tx.cardNo);
                    uploadMsg.set(3, "000000");
                    uploadMsg.set(4, tx.amount);
                    uploadMsg.set(11, tx.stan);
                    uploadMsg.set(14, tx.expiry);
                    uploadMsg.set(41, "TERMID01");

                    socket = new Socket("127.0.0.1", 5000);
                    socket.getOutputStream().write(addTPDUAndLength(uploadMsg.pack()));
                    int detailLen = socket.getInputStream().read(new byte[2048]);
                    socket.close();

                    if (detailLen != -1 && listener != null) {
                        listener.onSettlementLog("   [✓] Uploaded Record " + (i + 1) + " of " + txList.size() + " (STAN: " + tx.stan + ")");
                    }
                }

                // Step C: Flush DB & Return combined 0500 and 0510 payloads separated by '|'
                dbHelper.clearBatch();
                if (listener != null) {
                    listener.onTransactionComplete(reconResponseCode, saved0500HexPayload + "|" + saved0510HexPayload);
                }
            }
        } catch (Exception e) {
            if (listener != null) listener.onTransactionFailed("Execution Fault: " + e.getMessage());
        }
    }
}