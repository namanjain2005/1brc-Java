public class CountryStat {
    public double MaxVal;
    public double MinVal;
    public double Sum;
    public long Count;

    CountryStat(double val) {
       this.MinVal = val;
       this.MaxVal = val;
       this.Sum = val;
       this.Count = 1;
    }

    public void update(double val){
        this.MaxVal = Math.max(this.MaxVal, val);
        this.MinVal = Math.min(this.MinVal, val);
        this.Sum += val;
        this.Count++;
    }
}
