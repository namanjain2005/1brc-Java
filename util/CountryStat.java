package util;

public class CountryStat {
    public long MaxVal;
    public long MinVal;
    public long Sum;
    public long Count;

    public CountryStat(long val) {
       this.MinVal = val;
       this.MaxVal = val;
       this.Sum = val;
       this.Count = 1;
    }

    public void update(long val){
        this.MaxVal = Math.max(this.MaxVal, val);
        this.MinVal = Math.min(this.MinVal, val);
        this.Sum += val;
        this.Count++;
    }
}
