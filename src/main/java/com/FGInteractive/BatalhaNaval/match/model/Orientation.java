package com.FGInteractive.BatalhaNaval.match.model;

/** Placement axis; availability depends on the selected mode's placement rule. */
public enum Orientation {
    HORIZONTAL, VERTICAL,
    TRI_RIGHT, TRI_DOWN_LEFT, TRI_DOWN_RIGHT,
    HEX_VERTICAL, HEX_DIAGONAL_UP, HEX_DIAGONAL_DOWN
}
