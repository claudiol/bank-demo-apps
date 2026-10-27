package com.northwater;

import java.io.Serializable;
import java.util.List;

/** Template values. Public fields are what Thymeleaf reads. */
@SuppressWarnings("PMD.MissingStaticMethodInNonInstantiatableClass")
public final class Views {
    private Views() {}

    public static final class Flash implements Serializable {
        private static final long serialVersionUID = 1L;

        public final String kind;
        public final String text;

        public Flash(String kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    public static final class Person {
        public final String fullName;
        public final String email;

        public Person(String fullName, String email) {
            this.fullName = fullName;
            this.email = email;
        }
    }

    public static final class UserView {
        public String fullName;
        public String firstName;
        public String email;
        public String city;
        public String memberSince;
    }

    public static final class AccountView {
        public int id;
        public String nickname;
        public String kindLabel;
        public String mask;
        public String number;
        public String routing;
        public String balance;
        public long balanceCents;
        public String dollars;
        public String centsPart;
        public String opened;
        public String apy;
    }

    public static final class TxView {
        public int accountId;
        public String posted;
        public String description;
        public String memo;
        public String category;
        public String amount;
        public String direction;
        public String balance;
        public String account;
    }

    public static final class Filter {
        public String href;
        public String label;
        public boolean current;
    }

    public static final class Line {
        public final String label;
        public final String value;

        public Line(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    public static final class Review {
        public String eyebrow;
        public String headline;
        public String nonce;
        public String confirmLabel;
        public String editHref;
        public String note;
        public List<Line> lines;
    }

    public static final class Receipt {
        public String kind;
        public String confirmation;
        public String headline;
        public int fromId;
        public List<Line> lines;
    }
}
