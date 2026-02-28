package com.qb.analytics.model;

public class ForecastPoint {
    private String date; // yyyy-MM-dd
    private double predictedSales;
    private double confidenceLow;
    private double confidenceHigh;

    public ForecastPoint() {}

    public ForecastPoint(String date, double predictedSales, double confidenceLow, double confidenceHigh) {
        this.date = date;
        this.predictedSales = predictedSales;
        this.confidenceLow = confidenceLow;
        this.confidenceHigh = confidenceHigh;
    }

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }

    public double getPredictedSales() { return predictedSales; }
    public void setPredictedSales(double predictedSales) { this.predictedSales = predictedSales; }
    
    public double getConfidenceLow() { return confidenceLow; }
    public void setConfidenceLow(double confidenceLow) { this.confidenceLow = confidenceLow; }
    
    public double getConfidenceHigh() { return confidenceHigh; }
    public void setConfidenceHigh(double confidenceHigh) { this.confidenceHigh = confidenceHigh; }
    
    public double getPredicted() { return predictedSales; }
    
    public double getLow() { return confidenceLow; }
    public double getHigh() { return confidenceHigh; }
}
