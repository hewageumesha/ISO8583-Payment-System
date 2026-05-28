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
    private Button btnFireSale;
    private EditText etCardNo, etAmount, etExpiry, etCVV;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvSystemLog = findViewById(R.id.tvSystemLog);
        btnFireSale = findViewById(R.id.btnFireSale);

        etCardNo = findViewById(R.id.etCardNo);
        etAmount = findViewById(R.id.etAmount);
        etExpiry = findViewById(R.id.etExpiry);
        etCVV = findViewById(R.id.etCVV);

        switchServer = new SwitchServer();
        switchServer.start();

        tvSystemLog.setText("System Workspace Status: Online\n" +
                "Internal Switch Socket bound on Port 5000.\n" +
                "Awaiting ISO 8583 validation entry parameters...");

        btnFireSale.setOnClickListener(v -> {
            String card = etCardNo.getText().toString().trim();
            String amt = etAmount.getText().toString().trim();
            String exp = etExpiry.getText().toString().trim();
            String cvvCode = etCVV.getText().toString().trim();

            if (card.isEmpty() || amt.isEmpty() || exp.isEmpty() || cvvCode.isEmpty()) {
                showValidationError("All fields are required!");
                return;
            }

            if (!card.matches("^\\d{16}$")) {
                showValidationError("Invalid Card Number! Must be exactly 16 numeric digits.");
                return;
            }

            if (!cvvCode.matches("^\\d{3}$")) {
                showValidationError("Invalid CVV! Security code must be exactly 3 digits.");
                return;
            }

            if (!amt.matches("^\\d+$") || Long.parseLong(amt) <= 0) {
                showValidationError("Invalid Amount! Must be a positive number greater than 0.");
                return;
            }

            if (!exp.matches("^\\d{4}$")) {
                showValidationError("Invalid Expiry Format! Use YYMM (e.g., 2812).");
                return;
            }

            int month = Integer.parseInt(exp.substring(2, 4));
            if (month < 1 || month > 12) {
                showValidationError("Invalid Expiry Month! MM must be between 01 and 12.");
                return;
            }

            tvSystemLog.setText("⚡ CLIENT VALIDATION PASSED:\nBuilding MTI 0200 Payload frame data streams...");

            new PosTerminal(card, amt, exp, cvvCode, MainActivity.this).start();
        });
    }

    private void showValidationError(String message) {
        tvSystemLog.setText("CLIENT VALIDATION ERROR:\n" + message);
    }

    @Override
    public void onTransactionComplete(final String responseCode, final String rawPayloadHex) {
        runOnUiThread(() -> {
            try {
                // 1. Convert raw Hex string back into byte data arrays
                byte[] rawBytes = ISOUtils.hexStringToByteArray(rawPayloadHex);

                // 2. Extract structural wrapper elements cleanly
                String lengthHex = rawPayloadHex.substring(0, 4);
                String tpduHex = rawPayloadHex.substring(4, 14);

                // 3. Isolate raw ISO payload block (skipping 2 bytes length + 5 bytes TPDU = 7 bytes offset)
                byte[] isoPayload = Arrays.copyOfRange(rawBytes, 7, rawBytes.length);

                ISO87BPackager packager = new ISO87BPackager();
                ISOMsg responseMsg = new ISOMsg();
                responseMsg.setPackager(packager);
                responseMsg.unpack(isoPayload);

                // 4. FIX: Extract MTI and Bitmap bytes using explicit fixed offsets
                String mti = responseMsg.getMTI();

                // The primary bitmap is always exactly 8 bytes long, starting right after the 4-character MTI
                byte[] bitmapBytes = Arrays.copyOfRange(isoPayload, 4, 12);
                String bitmapHex = ISOUtils.bytesToHex(bitmapBytes, bitmapBytes.length);
                String bitmapBinary = convertHexToBinaryFormatted(bitmapHex);

                // 5. Construct the UI display breakdown layout
                StringBuilder sb = new StringBuilder();

                sb.append("==============================\n");
                sb.append("     TRANSACTION STATUS       \n");
                sb.append("==============================\n\n");
                if ("00".equals(responseCode)) {
                    sb.append("STATUS: [APPROVED]\n");
                } else {
                    sb.append("STATUS: [DECLINED / CODE: ").append(responseCode).append("]\n");
                }
                sb.append("\nRAW HEX PACKET:\n").append(rawPayloadHex).append("\n\n");

                sb.append("==============================\n");
                sb.append("   ISO8583 PACKET BREAKDOWN   \n");
                sb.append("==============================\n\n");
                sb.append("HEX LENGTH : ").append(lengthHex).append("\n");
                sb.append("TPDU DATA  : ").append(tpduHex).append("\n");
                sb.append("MTI (Type) : ").append(mti).append(" (Financial Response)\n");
                sb.append("BITMAP HEX : ").append(bitmapHex).append("\n\n");
                sb.append("BITMAP BINARY MAP:\n").append(bitmapBinary).append("\n\n");
                sb.append("----------- FIELDS -----------\n");

                // Dynamically fetch and present each validated field present in the message
                for (int i = 1; i <= responseMsg.getMaxField(); i++) {
                    if (responseMsg.hasField(i)) {
                        String fieldName = getFieldNameDescription(i);
                        String fieldValue = responseMsg.getString(i);
                        sb.append(String.format(Locale.US, "DE %03d [%s]\n   -> Value: %s\n", i, fieldName, fieldValue));
                    }
                }

                tvSystemLog.setText(sb.toString());

            } catch (Exception e) {
                tvSystemLog.setText("PARSING FAULT: Layout offset misalignment error.\nRaw Hex:\n" + rawPayloadHex);
            }
        });
    }

    @Override
    public void onTransactionFailed(final String errorReason) {
        runOnUiThread(() -> tvSystemLog.setText("SYSTEM TIMEOUT / FAILURE:\n" + errorReason));
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