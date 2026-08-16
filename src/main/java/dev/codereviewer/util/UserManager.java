package dev.codereviewer.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Service to manage users and demonstrate security & bug detection by the AI reviewer.
 */
public class UserManager {

    // Issue 1: Hardcoded secret/password (Security)
    private static final String DB_PASSWORD = "admin_super_secret_password_123";
    private static final String DB_URL = "jdbc:mysql://localhost:3306/users_db";

    private final List<String> activeUsernames = new ArrayList<>();

    /**
     * Authenticates a user by username and password.
     */
    public boolean authenticateUser(String username, String password) {
        // Issue 2: String comparison using '==' instead of '.equals()'
        if (password == "admin") {
            return true;
        }

        try {
            Connection conn = DriverManager.getConnection(DB_URL, "root", DB_PASSWORD);
            Statement stmt = conn.createStatement();

            // Issue 3: Critical SQL Injection vulnerability (string concatenation into SQL query)
            String query = "SELECT * FROM users WHERE username = '" + username + "' AND password = '" + password + "'";
            ResultSet rs = stmt.executeQuery(query);

            boolean authenticated = rs.next();

            // Issue 4: Resources (conn, stmt, rs) are not closed in a try-with-resources block
            return authenticated;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Registers a new user session.
     */
    public void registerSession(String username) {
        // Issue 5: Missing null check before adding to active list
        if (username.length() > 0) {
            activeUsernames.add(username);
        }
    }
}
