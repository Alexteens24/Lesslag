package com.lesslag;

public interface PremiumService {
    void sendAlert(String message);

    boolean isEnabled();

    void reload();
}
