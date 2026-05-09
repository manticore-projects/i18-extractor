package com.example;

import javax.swing.*;

public class LoginPanel extends JPanel {

    public LoginPanel() {
        JLabel title = new JLabel();
        JLabel error = new JLabel();
        JButton submit = new JButton();

        title.setText("Account Overview");           // duplicate of AccountPanel — should reuse key
        submit.setText("Sign in");
        submit.setToolTipText("Click to sign in");
        error.setText("Invalid username or password");
    }
}
