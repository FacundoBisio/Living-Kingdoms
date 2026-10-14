package dev.livingkingdoms.profession.domain;

public enum ProfessionType {
    UNASSIGNED, FARMER, GUARD, BUILDER, BLACKSMITH, MERCHANT, CAPTAIN, MAYOR, LUMBERJACK, MINER;
    public boolean economic() { return this==LUMBERJACK || this==MINER || this==BLACKSMITH; }
    public boolean operational() { return economic() || this==FARMER || this==GUARD || this==BUILDER || this==MERCHANT; }
}
