using Microsoft.AspNetCore.Mvc;

namespace XstreamPlayList.Controllers
{
    [Route("player_api")]

    public class PlayerController : ControllerBase
    {
        private readonly ILogger<PlayerController> _logger;
        public PlayerController(ILogger<PlayerController> logger)
        {
            _logger = logger;
        }
        public IActionResult Index()
        {

        }
    }
}
