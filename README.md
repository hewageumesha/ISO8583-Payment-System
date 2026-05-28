# ISO 8583 Android Payment Terminal & Host Switch Simulator

This repository contains a full-stack native Android application designed to simulate financial transaction routing using the industry-standard **ISO 8583 protocol (1987 Binary Variant)**. The project features a localized Point of Sale (POS) Terminal emulation client running concurrently alongside an asynchronous background mock Banking Host Switch Engine over a local TCP loopback channel.

---

## 🚀 Features

- **ISO 8583 Message Packing/Unpacking**: Utilizes the robust Java framework `jPOS` engine paired with `ISO87BPackager` to compress and expand payment payloads via Binary Coded Decimal (BCD) matrix formats.
- **Embedded Mock Host Switch**: Includes a background `ServerSocket` multi-threaded processing layer listening locally on Port `5000` to evaluate core business validation rules dynamically.
- **Data Boundary Precision**: Zero-bleed data tracking implementation guaranteeing strict 12-digit fixed amount layouts (`Field 4`) without manual multiplication corruption errors.
- **Interactive UI Terminal Logs**: Renders a complete visual breakdown panel within the mobile interface displaying:
  - Total frame Hex packet lengths
  - Routing TPDU transport block headers (`6000150000`)
  - Dynamic binary bitmap truth-tables
  - Individual Data Element (DE) index translations
- **Clipboard Accessibility**: Enabled text selection directly within the logs for straightforward clipboard extraction and payload debugging analysis.

---

## 🗺️ Project Architecture & Data Flow

The application relies on localized socket streaming channels to route packets directly within the device loopback interface:

1. **User Input Capture**: The user enters the Card Number, Amount, Expiry Date, and CVV inside `MainActivity`.
2. **Client Packaging (`PosTerminal.java`)**: Converts inputs into an `ISOMsg` object, formats the amount into a 12-digit zero-padded string, packs it into a raw byte array, appends the Length + TPDU headers, and sends it over TCP.
3. **Host Processing (`SwitchServer.java`)**: Listens on port `5000`, extracts the ISO payload, evaluates business rules, clones the request message into a response format, updates the MTI to `0210`, attaches Field 39 (Response Code), packs it, and routes it back.
4. **UI Diagnostic Rendering**: `MainActivity` catches the response payload, strips headers, and generates a breakdown structure onto the screen.

### Supported Core Banking Rules (Validation Sandbox)
- **Routing Check**: Accepts requests only from Visa (`4...`) or Mastercard (`5...`) PAN ranges. Anything else returns Decline Code `05` (Do Not Honor).
- **Liveness Expiry Verification**: Evaluates the `YYMM` layout chronologically against the system clock. Expired cards return Decline Code `14`.
- **Insufficient Funds Emulation**: Triggered on demand if the transaction amount parameter ends exactly in `99` (e.g., LKR 99 or LKR 199), returning Code `51`.
- **Security Check**: Entering a CVV string of `999` trips a rule execution path returning Decline Code `05`.

---

## 🛠️ Data Element (DE) Layout Reference

The message structures traveling over the connection line adhere to the following data-type matrix patterns:

| Field Index | Field Name Description | Layout Type | Sample Payload Content |
| :--- | :--- | :--- | :--- |
| **MTI** | Message Type Identifier | Fixed 4 Digits | `0200` (Request) / `0210` (Response) |
| **Bitmap** | Primary Bitmask Flags | 8 Binary Bytes | `723C45802EC18004` (64-bit array map) |
| **DE 002** | Primary Account Number (PAN) | LVAR Numeric | `4544860000280216` |
| **DE 003** | Processing Code | Fixed 6 Digits | `000000` (Standard Retail Sale) |
| **DE 004** | Transaction Amount | Fixed 12 Digits| `000000000080` (LKR 80.00 equivalent) |
| **DE 011** | Systems Trace Audit Number (STAN)| Fixed 6 Digits | `938906` (Random Tracking Indicator) |
| **DE 014** | Expiration Date | Fixed 4 Digits | `2803` (Format: YYMM) |
| **DE 035** | Track 2 Data String Map | LVAR Alphanumeric| `4544860000280216=280310100000` |
| **DE 039** | Action Response Code | Fixed 2 Characters| `00` (Approved) / `51` (Insufficient Funds) |
| **DE 048** | Private Data Block Space | LLLVAR Text | `CVV=373` |

---

## 💻 Visual Diagnostics Format Output Example

When a transaction completes successfully, the application's terminal layout window provides the following detailed structure output:

```text
==============================
     TRANSACTION STATUS       
==============================

STATUS: [APPROVED]

RAW HEX PACKET:
003A6000150000021072200000000000001645448600002802160000000000803030

==============================
   ISO8583 PACKET BREAKDOWN   
==============================

HEX LENGTH : 003A
TPDU DATA  : 6000150000
MTI (Type) : 0210 (Financial Response)
BITMAP HEX : 7220000000000000

BITMAP BINARY MAP:
01110010 00100000 00000000 00000000 
00000000 00000000 00000000 00000000 

----------- FIELDS -----------
DE 002 [Primary Account Number]
   -> Value: 4544860000280216
DE 003 [Processing Code]
   -> Value: 000000
DE 004 [Transaction Amount]
   -> Value: 000000000080
DE 039 [Response Code]
   -> Value: 00

```

---

## ⚙️ Quick Installation & Setup

1. **Clone the Repository**
```bash
git clone [https://github.com/yourusername/iso8583-android-terminal.git](https://github.com/yourusername/iso8583-android-terminal.git)

```


2. **Open with Android Studio**
* Ensure you have the `jPOS` core library dependencies registered inside your local `build.gradle` configuration.


3. **Build and Run**
* Execute the project on an Android Device or Emulator target (API 30+ recommended).
* The integrated switch mock server instantiates instantly upon environment startup (`onCreate`).


4. **Test Transactions**
* Enter your card details, type an amount, and tap **Execute**. Long-press any portion of the output terminal if you wish to copy the raw payload stream.


---

## 📜 License

This architecture simulator code ecosystem is distributed under the MIT Open Source licensing agreements.

```

```
