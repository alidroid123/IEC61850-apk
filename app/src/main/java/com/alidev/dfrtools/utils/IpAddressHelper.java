package com.alidev.dfrtools.utils;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.widget.EditText;

public class IpAddressHelper {

    public static void setupIpInputs(EditText... editTexts) {
        if (editTexts.length != 4) return;

        for (int i = 0; i < 4; i++) {
            final int index = i;
            final EditText current = editTexts[index];
            final EditText next = (index < 3) ? editTexts[index + 1] : null;
            final EditText prev = (index > 0) ? editTexts[index - 1] : null;

            current.addTextChangedListener(new TextWatcher() {
                private String lastValue = "";

                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    lastValue = s.toString();
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    String str = s.toString();
                    
                    // Handle dot (.) input to move to next
                    if (str.contains(".")) {
                        String cleaned = str.replace(".", "");
                        if (!cleaned.equals(str)) {
                            current.setText(cleaned);
                            current.setSelection(cleaned.length());
                            if (next != null) {
                                next.requestFocus();
                                next.setSelection(next.getText().length());
                            }
                        }
                        return;
                    }

                    // Auto move if 3 digits
                    if (str.length() == 3 && next != null) {
                        next.requestFocus();
                        next.setSelection(next.getText().length());
                    }

                    // Range validation 0-255
                    if (!str.isEmpty()) {
                        try {
                            int val = Integer.parseInt(str);
                            if (val > 255) {
                                current.setText("255");
                                current.setSelection(3);
                            }
                        } catch (Exception ignored) {}
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });

            // Handle backspace to move to previous
            current.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_DEL && event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (current.getText().length() == 0 && prev != null) {
                        prev.requestFocus();
                        prev.setSelection(prev.getText().length());
                        return true;
                    }
                }
                return false;
            });
        }
    }

    public static String getIpFromInputs(EditText... editTexts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < editTexts.length; i++) {
            String part = editTexts[i].getText().toString().trim();
            if (part.isEmpty()) part = "0";
            sb.append(part);
            if (i < editTexts.length - 1) sb.append(".");
        }
        return sb.toString();
    }

    public static void setIpToInputs(String ip, EditText... editTexts) {
        if (ip == null || ip.isEmpty()) return;
        String[] parts = ip.split("\\.");
        for (int i = 0; i < Math.min(parts.length, editTexts.length); i++) {
            editTexts[i].setText(parts[i]);
        }
    }
}
