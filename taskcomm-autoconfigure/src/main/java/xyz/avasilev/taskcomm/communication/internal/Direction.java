package xyz.avasilev.taskcomm.communication.internal;

/** Направление коммуникации — колонка {@code direction}; в публичном API это тип, а не значение. */
public enum Direction {

    /** Входящая: внешняя сторона — {@code source}. */
    INBOUND,

    /** Исходящая: внешняя сторона — {@code destination}. */
    OUTBOUND
}
