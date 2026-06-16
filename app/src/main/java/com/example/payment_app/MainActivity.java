package com.example.payment_app;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.packager.ISO87BPackager;
import java.util.Arrays;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements PosTerminal.TerminalResultListener {

    private SwitchServer switchServer;
    private TextView tvSystemLog;
    private Button btnFireSale, btnSettlement, btnVoidSale;
    private EditText etCardNo, etAmount, etExpiry, etCVV, etInvoiceNo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // UI Components Binding
        tvSystemLog = findViewById(R.id.tvSystemLog);
        btnFireSale = findViewById(R.id.btnFireSale);
        btnSettlement = findViewById(R.id.btnSettlement);
        btnVoidSale = findViewById(R.id.btnVoidSale);

        etCardNo = findViewById(R.id.etCardNo);
        etAmount = findViewById(R.id.etAmount);
        etExpiry = findViewById(R.id.etExpiry);
        etCVV = findViewById(R.id.etCVV);
        etInvoiceNo = findViewById(R.id.etInvoiceNo);

        // Running SwitchServer inside a background thread to prevent UI freezing/crashing
        switchServer = new SwitchServer();
        new Thread(() -> {
            try {
                switchServer.start();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        tvSystemLog.setText("System Workspace Status: Online\n" +
                "Internal Switch Socket bound on Port 5000.\n" +
                "Awaiting ISO 8583 validation entry parameters...");

        // =========================================================
        //                    1. STANDARD SALE BUTTON
        // =========================================================
        btnFireSale.setOnClickListener(v -> {
            String card = etCardNo.getText().toString().trim();
            String amt = etAmount.getText().toString().trim();
            String exp = etExpiry.getText().toString().trim();
            String cvvCode = etCVV.getText().toString().trim();

            if (card.isEmpty() || amt.isEmpty() || exp.isEmpty() || cvvCode.isEmpty()) {
                showValidationError("All fields are required for Sale!");
                return;
            }

            if (!card.matches("^\\d{16}$")) {
                showValidationError("Invalid Card Number! Must be exactly 16 digits.");
                return;
            }

            if (!cvvCode.matches("^\\d{3}$")) {
                showValidationError("Invalid CVV! Security code must be 3 digits.");
                return;
            }

            if (!amt.matches("^\\d+$") || Long.parseLong(amt) <= 0) {
                showValidationError("Invalid Amount! Must be a positive number.");
                return;
            }

            if (!exp.matches("^\\d{4}$")) {
                showValidationError("Invalid Expiry! Use YYMM (e.g., 2812).");
                return;
            }

            tvSystemLog.setText("CLIENT VALIDATION PASSED:\nBuilding MTI 0200 Payload frame data streams...");
            // Standard Sale Request (6 Parameters)
            new PosTerminal(MainActivity.this, card, amt, exp, cvvCode, MainActivity.this).start();
        });

        // =========================================================
        //                    2. VOID TRANSACTION BUTTON
        // =========================================================
        btnVoidSale.setOnClickListener(v -> {
            String card = etCardNo.getText().toString().trim();
            String exp = etExpiry.getText().toString().trim();
            String cvvCode = etCVV.getText().toString().trim();
            String invoice = etInvoiceNo.getText().toString().trim();

            if (card.isEmpty() || exp.isEmpty() || cvvCode.isEmpty() || invoice.isEmpty()) {
                showValidationError("Card details AND Invoice Number are required for Void!");
                return;
            }

            if (!card.matches("^\\d{16}$")) {
                showValidationError("Invalid Card Number! Must be exactly 16 digits.");
                return;
            }

            if (!cvvCode.matches("^\\d{3}$")) {
                showValidationError("Invalid CVV! Security code must be 3 digits.");
                return;
            }

            if (!exp.matches("^\\d{4}$")) {
                showValidationError("Invalid Expiry! Use YYMM (e.g., 2812).");
                return;
            }

            tvSystemLog.setText("⚡ INITIALIZING VOID SALE TRANSACTION...\nPackaging Void frames for Invoice: " + invoice);

            // FIX: මෙතනදී "VOID" කියන එකත් එක්ක පරාමිති 7ක් ගන්න අලුත් Constructor එක නිවැරදිව කෝල් කරනවා!
            // එතකොට තමයි PosTerminal එක ඇතුලෙන් Processing Code එක '020000' වෙලා සර්වර් එකට යන්නේ.
            new PosTerminal(MainActivity.this, card, invoice, exp, cvvCode, "VOID", MainActivity.this).start();
        });

        // =========================================================
        //                    3. SETTLEMENT BUTTON
        // =========================================================
        btnSettlement.setOnClickListener(v -> {
            tvSystemLog.setText("⚡ INITIALIZING BATCH RECONCILIATION SETTLEMENT...");
            // Standard Settlement Request (2 Parameters)
            new PosTerminal(MainActivity.this, MainActivity.this).start();
        });
    }

    private void showValidationError(String message) {
        tvSystemLog.setText("CLIENT VALIDATION ERROR:\n" + message);
    }

    @Override
    public void onTransactionComplete(final String responseCode, final String rawPayloadHex) {
        runOnUiThread(() -> {
            try {
                if (rawPayloadHex == null || rawPayloadHex.isEmpty()) {
                    tvSystemLog.setText("ERROR: Received empty payload from terminal.");
                    return;
                }

                StringBuilder sb = new StringBuilder();
                String[] payloads = rawPayloadHex.split("\\|");

                byte[] firstRawBytes = ISOUtils.hexStringToByteArray(payloads[0]);
                byte[] firstIsoPayload = Arrays.copyOfRange(firstRawBytes, 7, firstRawBytes.length);
                ISO87BPackager packager = new ISO87BPackager();
                ISOMsg firstMsg = new ISOMsg();
                firstMsg.setPackager(packager);
                firstMsg.unpack(firstIsoPayload);
                String firstMti = firstMsg.getMTI();

                if ("0500".equals(firstMti) && payloads.length >= 2) {
                    sb.append("=========================================\n");
                    sb.append("         BATCH SETTLEMENT REPORT         \n");
                    sb.append("=========================================\n");
                    sb.append("STATUS: ").append("00".equals(responseCode) ? "[APPROVED / SUCCESS]" : "[DECLINED / FAILED]").append("\n\n");
                    sb.append(generateIsoBreakdownMarkup("0500 REQUEST PACKET (Terminal -> Host)", payloads[0]));
                    sb.append("\n-----------------------------------------\n\n");
                    sb.append(generateIsoBreakdownMarkup("0510 RESPONSE PACKET (Host -> Terminal)", payloads[1]));

                } else if ("0200".equals(firstMti) && payloads.length >= 2) {
                    // =========================================================
                    //         FINANCIAL TRANSACTION MODE (SALE / VOID)
                    // =========================================================
                    boolean isVoidSale = false;
                    if (firstMsg.hasField(3)) {
                        String procCode = firstMsg.getString(3);
                        if (procCode != null && procCode.startsWith("02")) {
                            isVoidSale = true;
                        }
                    }

                    sb.append("=========================================\n");
                    if (isVoidSale) {
                        sb.append("         VOID TRANSACTION STATUS         \n");
                    } else {
                        sb.append("         FINANCIAL TRANSACTION STATUS    \n");
                    }
                    sb.append("=========================================\n");

                    if (isVoidSale) {
                        sb.append("STATUS: ").append("00".equals(responseCode) ? "[VOID APPROVED]" : "[VOID DECLINED / CODE: " + responseCode + "]").append("\n\n");
                        sb.append(generateIsoBreakdownMarkup("0200 VOID REQUEST (Terminal -> Host)", payloads[0]));
                        sb.append("\n-----------------------------------------\n\n");
                        sb.append(generateIsoBreakdownMarkup("0210 VOID RESPONSE (Host -> Terminal)", payloads[1]));
                    } else {
                        sb.append("STATUS: ").append("00".equals(responseCode) ? "[APPROVED]" : "[DECLINED / CODE: " + responseCode + "]").append("\n\n");
                        sb.append(generateIsoBreakdownMarkup("0200 REQUEST PACKET (Terminal -> Host)", payloads[0]));
                        sb.append("\n-----------------------------------------\n\n");
                        sb.append(generateIsoBreakdownMarkup("0210 RESPONSE PACKET (Host -> Terminal)", payloads[1]));
                    }
                }

                tvSystemLog.setText(sb.toString());

            } catch (Exception e) {
                tvSystemLog.setText("PARSING FAULT: Unpack error.\nDetails: " + e.getMessage() + "\nRaw Hex Data:\n" + rawPayloadHex);
            }
        });
    }

    private String generateIsoBreakdownMarkup(String title, String hexPayload) throws Exception {
        byte[] rawBytes = ISOUtils.hexStringToByteArray(hexPayload);
        String lengthHex = hexPayload.substring(0, 4);
        String tpduHex = hexPayload.substring(4, 14);
        byte[] isoPayload = Arrays.copyOfRange(rawBytes, 7, rawBytes.length);

        ISO87BPackager packager = new ISO87BPackager();
        ISOMsg msg = new ISOMsg();
        msg.setPackager(packager);
        msg.unpack(isoPayload);

        String mti = msg.getMTI();
        byte[] bitmapBytes = Arrays.copyOfRange(isoPayload, 4, 12);
        String bitmapHex = ISOUtils.bytesToHex(bitmapBytes, bitmapBytes.length);
        String bitmapBinary = convertHexToBinaryFormatted(bitmapHex);

        StringBuilder sb = new StringBuilder();
        sb.append(">>> ").append(title).append(" <<<\n");
        sb.append("RAW HEX    : ").append(hexPayload).append("\n\n");
        sb.append("HEX LENGTH : ").append(lengthHex).append("\n");
        sb.append("TPDU DATA  : ").append(tpduHex).append("\n");
        sb.append("MTI (Type) : ").append(mti).append("\n");
        sb.append("BITMAP HEX : ").append(bitmapHex).append("\n");
        sb.append("BITMAP BINARY MAP:\n").append(bitmapBinary).append("\n\n");
        sb.append("----------- FIELDS -----------\n");

        for (int i = 1; i <= msg.getMaxField(); i++) {
            if (msg.hasField(i)) {
                String fieldName = getFieldNameDescription(i);
                String fieldValue = msg.getString(i);
                sb.append(String.format(Locale.US, "DE %03d [%s]\n   -> Value: %s\n", i, fieldName, fieldValue));
            }
        }
        return sb.toString();
    }

    @Override
    public void onTransactionFailed(final String errorReason) {
        runOnUiThread(() -> tvSystemLog.setText("SYSTEM TIMEOUT / FAILURE:\n" + errorReason));
    }

    @Override
    public void onSettlementLog(String log) {
        runOnUiThread(() -> tvSystemLog.append("\n" + log));
    }

    private String convertHexToBinaryFormatted(String hexStr) {
        StringBuilder binaryBuilder = new StringBuilder();
        for (int i = 0; i < hexStr.length(); i++) {
            String binChar = Integer.toBinaryString(Character.digit(hexStr.charAt(i), 16));
            binChar = String.format("%4s", binChar).replace(' ', '0');
            binaryBuilder.append(binChar);

            if ((i + 1) % 2 == 0) {
                binaryBuilder.append(" ");
            }
            if ((i + 1) % 8 == 0 && (i + 1) < hexStr.length()) {
                binaryBuilder.append("\n");
            }
        }
        return binaryBuilder.toString().trim();
    }

    private String getFieldNameDescription(int fieldId) {
        switch (fieldId) {
            case 2: return "Primary Account Number";
            case 3: return "Processing Code";
            case 4: return "Transaction Amount";
            case 7: return "Transmission Date & Time";
            case 11: return "Systems Trace Audit No";
            case 12: return "Local Time of Transaction";
            case 13: return "Local Date of Transaction";
            case 14: return "Expiration Date";
            case 18: return "Merchant Type / MCC";
            case 22: return "POS Entry Mode";
            case 24: return "Network International ID";
            case 25: return "POS Condition Code";
            case 35: return "Track 2 Data";
            case 37: return "Retrieval Reference No";
            case 38: return "Authorization Code";
            case 39: return "Response Code";
            case 41: return "Card Acceptor Terminal ID";
            case 42: return "Card Acceptor Ident Code";
            case 48: return "Private Data / CVV";
            case 49: return "Currency Code";
            case 62: return "Invoice / Ticket Number";
            case 90: return "Original Data Elements";
            default: return "Custom Field Attribute";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (switchServer != null) {
            switchServer.shutdown();
        }
    }
}