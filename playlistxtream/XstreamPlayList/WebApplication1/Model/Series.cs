namespace XstreamPlayList.Model
{
    public class Series
    {
        public int Num { get; set; }
        public string Name { get; set; }
        public int SeriesId { get; set; }
        public string Cover { get; set; }
        public string Plot { get; set; }
        public string Cast { get; set; }
        public string Director { get; set; }
        public string Genre { get; set; }
        public string ReleaseDate { get; set; }
        public string LastModified { get; set; }
        public string Rating { get; set; }
        public string Rating5Based { get; set; }
        public List<string> BackdropPath { get; set; }
        public string YoutubeTrailer { get; set; }
        public string Tmdb { get; set; }
        public string EpisodeRunTime { get; set; }
        public string CategoryId { get; set; }
        public List<int> CategoryIds { get; set; }
    }
}
