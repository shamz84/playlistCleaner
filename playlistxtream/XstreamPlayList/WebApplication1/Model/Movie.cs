namespace XstreamPlayList.Model
{
    public class Movie
    {
        public int Num { get; set; }
        public string Name { get; set; }
        public string StreamType { get; set; }
        public int StreamId { get; set; }
        public string StreamIcon { get; set; }
        public string Rating { get; set; }
        public double Rating5Based { get; set; }
        public string Tmdb { get; set; }
        public string Trailer { get; set; }
        public string Added { get; set; }
        public int IsAdult { get; set; }
        public string CategoryId { get; set; }
        public List<int> CategoryIds { get; set; }
        public string ContainerExtension { get; set; }
        public string CustomSid { get; set; }
        public string DirectSource { get; set; }
    }
}
