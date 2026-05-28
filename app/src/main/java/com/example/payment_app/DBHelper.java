package com.example.payment_app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public class DBHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "payment_app.db";
    private static final int DATABASE_VERSION = 1;

    // FIX: Pass the name, factory (null), and version to the super constructor
    public DBHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // Keeps track of elements required to construct MTI 0320 details logs
        db.execSQL("CREATE TABLE tx_history (id INTEGER PRIMARY KEY AUTOINCREMENT, card_no TEXT, amount TEXT, expiry TEXT, stan TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS tx_history");
        onCreate(db);
    }

    public void insertTransaction(String cardNo, String amount, String expiry, String stan) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("card_no", cardNo);
        values.put("amount", amount);
        values.put("expiry", expiry);
        values.put("stan", stan);
        db.insert("tx_history", null, values);
    }

    public List<TxModel> getAllTransactions() {
        List<TxModel> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT * FROM tx_history", null);
        if (cursor.moveToFirst()) {
            do {
                list.add(new TxModel(
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3),
                        cursor.getString(4)
                ));
            } while (cursor.moveToNext());
        }
        cursor.close();
        return list;
    }

    public void clearBatch() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.execSQL("DELETE FROM tx_history");
    }

    public static class TxModel {
        public String cardNo, amount, expiry, stan;
        public TxModel(String cardNo, String amount, String expiry, String stan) {
            this.cardNo = cardNo;
            this.amount = amount;
            this.expiry = expiry;
            this.stan = stan;
        }
    }
}