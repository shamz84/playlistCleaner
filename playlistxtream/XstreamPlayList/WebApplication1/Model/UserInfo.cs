namespace XstreamPlayList.Model;

public class UserInfo
{
    public string Username { get; set; }
    public string Password { get; set; }
    public string Message { get; set; }
    public int Auth { get; set; }
    public string Status { get; set; }
    public long ExpDate { get; set; }
    public string IsTrial { get; set; }
    public string ActiveCons { get; set; }
    public long CreatedAt { get; set; }
    public int MaxConnections { get; set; }
    public List<string> AllowedOutputFormats { get; set; }
}
