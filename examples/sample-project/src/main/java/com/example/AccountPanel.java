package com.example;

import java.text.MessageFormat;
import javax.swing.*;

/**
 * A sample panel exercising all four extraction shapes.
 * Run i18n-extractor against examples/sample-project to see the output.
 */
public class AccountPanel extends JPanel {

    private final JLabel titleLabel = new JLabel();
    private final JLabel statusLabel = new JLabel();
    private final JLabel balanceLabel = new JLabel();
    private final JLabel formattedLabel = new JLabel();
    private final JLabel mfLabel = new JLabel();
    private final JLabel longLabel = new JLabel();

    public AccountPanel(Account acct, double balance, int rows, long ms) {

        // 1. Plain string literal
        titleLabel.setText("Account Overview");
        titleLabel.setToolTipText("Hover for details");

        // 2. + concatenation
        statusLabel.setText("Found " + rows + " records in " + ms + "ms");

        // 3. String.format
        balanceLabel.setText(String.format("Balance: %.2f (as of today)", balance));
        formattedLabel.setText(String.format("User %2$s could not access %1$s",
            acct.getResource(), acct.getOwner()));

        // 4. MessageFormat.format passthrough
        mfLabel.setText(MessageFormat.format(
            "Migration complete: {0} rows in {1,number} ms", rows, ms));

        // 5. Inline StringBuilder
        longLabel.setText(new StringBuilder()
            .append("Account ").append(acct.getNumber())
            .append(" balance: ").append(balance).toString());

        // 6. Multi-statement StringBuilder
        StringBuilder sb = new StringBuilder();
        sb.append("Account ");
        sb.append(acct.getNumber());
        sb.append(" balance: ").append(balance);
        sb.append(" as of ");
        sb.append("today");
        JOptionPane.showMessageDialog(this, sb.toString(),
            "Account Info", JOptionPane.INFORMATION_MESSAGE);

        // 7. Nested: format inside concat
        JOptionPane.showMessageDialog(this,
            "Warning: " + String.format("only %d slots left in %s", rows, "EU"));

        // 8. Things that should NOT be extracted
        String sql = "SELECT * FROM accounts WHERE id = ?";       // SQL
        String prop = "account.balance.threshold";                 // property key
        String url = "https://api.bank.example.com/v1/accounts";   // URL
        System.out.println("DEBUG: " + sql + prop + url);          // log, no UI sink
    }

    interface Account {
        String getNumber();
        String getOwner();
        String getResource();
    }

    // -- Helper methods returning user-visible String text -------------------------
    // These get extracted via Pass 2 (method-return scanner) because the actual
    // UI sink (setText / showMessageDialog) is in some other class that calls
    // getWelcomeText() etc. The "substantial text" filter (>= 2 newlines or >= 100
    // chars) keeps this from over-extracting toString-style returns.

    private static final String APPLICATION_NAME = "IFRSBox";

    public static String getWelcomeText() {
        return "\n"
            + "You are running manticore-"
            + APPLICATION_NAME
            + " Version "
            + System.getProperty("build.version.tag")
            + "\n"
            + "-------------------------------------------------------------------\n"
            + "Copyright (C) 2014 manticore-projects.com\n\n"
            + "This program is distributed in the hope that it will be useful, but\n"
            + "WITHOUT ANY WARRANTY; without even the implied warranty of\n"
            + "MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.\n";
    }

    // Should NOT be extracted — looks like a toString-style helper, single line, short
    @Override
    public String toString() {
        return "AccountPanel{title=" + titleLabel.getText() + "}";
    }

    // Should NOT be extracted — short message
    public String getError(String code) {
        return "Error " + code;
    }
}
