namespace XstreamPlayList.Model;

public class ServerInfo
{
    public bool Xcms { get; set; }
    public string Version { get; set; }
    public string Revision { get; set; }
    public string Url { get; set; }
    public string Port { get; set; }
    public string HttpsPort { get; set; }
    public string ServerProtocol { get; set; }
    public string RtmpPort { get; set; }
    public long TimestampNow { get; set; }
    public string TimeNow { get; set; }
    public string Timezone { get; set; }
}