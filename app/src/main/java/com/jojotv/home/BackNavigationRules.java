package com.jojotv.home;

final class BackNavigationRules {
    enum Action { CANCEL_MOVE, DISMISS_POPUP, POP_SCREEN, GO_HOME, STAY_HOME }

    private BackNavigationRules() { }

    static Action nextAction(boolean movingApps, boolean popupVisible, boolean hasBackStack, boolean hasActiveScreen) {
        if (movingApps) return Action.CANCEL_MOVE;
        if (popupVisible) return Action.DISMISS_POPUP;
        if (hasBackStack) return Action.POP_SCREEN;
        return hasActiveScreen ? Action.GO_HOME : Action.STAY_HOME;
    }
}
