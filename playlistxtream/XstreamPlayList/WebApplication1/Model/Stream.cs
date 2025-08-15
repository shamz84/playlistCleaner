namespace XstreamPlayList.Model
{
    public class Stream
    {
        public int Num { get; set; }
        public string Name { get; set; }
        public string StreamType { get; set; }
        public int StreamId { get; set; }
        public string StreamIcon { get; set; }
        public string EpgChannelId { get; set; }
        public string Added { get; set; }
        public int IsAdult { get; set; }
        public string CategoryId { get; set; }
        public List<int> CategoryIds { get; set; }
        public string CustomSid { get; set; }
        public int TvArchive { get; set; }
        public string DirectSource { get; set; }
        public int TvArchiveDuration { get; set; }
    }
}
