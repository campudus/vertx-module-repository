var sid;
var currentModId = 0;

function fillLatestApprovedMods() {
  $.getJSON('/latest-approved-modules', function(data) {
    $('#latestApprovedModules').empty();
    $('#latestApprovedModules').append(getLiFromMods(data.modules));
  });
}

function getLiFromMods(modules) {
  var item, items = '<ul>';
  while (modules.length) {
    item = modules.pop();
    items += '<li class="mod" id="' + item._id + '"><div class="modname"><span class="date">'
        + formatTimestamp(item.timeApproved) + '</span> - <a href="' + item.projectUrl + '">'
        + item.owner + '~' + item.name + '~' + item.version + '</a></div></li>';
  }
  items += '</ul>';
  return items;
}

function formatTimestamp(time) {
  function twoDigits(num) {
    if (num < 10)
      return '0' + num;
    else
      return num;
  }

  var date = new Date(time);
  var months = [ 'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec' ];
  var year = date.getFullYear();
  var month = months[date.getMonth()];
  var day = date.getDate();
  var hour = date.getHours();
  var min = date.getMinutes();
  var sec = date.getSeconds();
  var time = twoDigits(day) + ' ' + month + ' ' + year + ' ' + twoDigits(hour) + ':'
      + twoDigits(min) + ':' + twoDigits(sec);

  return time;
}

function createSearchFormSubmitHandler() {
  $('#searchForm').submit(function(event) {
    event.preventDefault();
    $.post('/search', {
      query : $('#query').val()
    }, function(data) {
      $('#searchResults').empty();
      if ($.isEmptyObject(data.modules)) {
        $('#searchResults').append('<p>Sorry, I couldn\'t find anything :(</p>');
      } else {
        $('#searchResults').append(getLiFromMods(data.modules));
      }
      $('#searchResultContainer').show();
    }, 'json');
  });
}

function createRegisterFormHandler() {
  $('a.showRegister').click(showRegisterForm);
  function showRegisterForm() {
    $('#registerFormContainer').show();
    displayHideLink();
    return false;
  }

  function displayHideLink() {
    $('a.showRegister').replaceWith('<a class="hideRegister href-less">registration form</a>')
    $('a.hideRegister').click(function() {
      $('#registerFormContainer').hide();
      displayShowLink();
    });
  }

  function displayShowLink() {
    $('a.hideRegister').replaceWith('<a class="showRegister href-less">registration form</a>')
    $('a.showRegister').click(showRegisterForm);
  }
}

function createUnapprovedModsHandler() {
  var loginContainer = '#loginContainer';

  function resetLoginForm() {
    $(loginContainer + ' #password').val('');
    $(loginContainer + ' .errorMessage').hide();
  }

  function hideLoginForm() {
    $(loginContainer).fadeOut(300);
    $('#mask').fadeOut(300, function() {
      $(this).remove();
    });
  }

  function showLoginErrorMessage(error) {
    if ($(loginContainer).is(':hidden')) {
      resetLoginForm();
      showLoginForm();
    }
    $(loginContainer + ' .errorMessage').show().html(error);
  }

  function showLoginForm() {
    $(loginContainer).fadeIn(300);
    $('#password').focus();

    // Align the box
    var margTop = $(loginContainer).height() / 2;
    var margLeft = $(loginContainer).width() / 2;

    $(loginContainer).css({
      'margin-top' : -margTop,
      'margin-left' : -margLeft
    });

    // Add mask to hide the background
    $('body').append('<div id="mask"></div>');
    $('#mask').fadeIn(300);

    $("#mask").click(function() {
      hideLoginForm()
      return false;
    });
  }

  $('a.showUnapproved').click(showUnapproved);

  function showUnapproved() {
    if (sid) {
      getUnapproved()
    } else {
      resetLoginForm();
      showLoginForm();
    }

    return false;
  }

  $('a.close').click(function() {
    hideLoginForm();
    return false;
  });

  $('#loginForm').submit(function(event) {
    event.preventDefault();
    $.post('/login', {
      password : $('#password').val()
    }, processLoginResult, 'json');
  });

  function processLoginResult(data) {
    if (data.status == 'ok' && data.sessionID) {
      sid = data.sessionID
      getUnapproved();
    } else if (data.status == 'error') {
      processStatusError(data);
    } else if (data.status == 'denied') {
      showLoginErrorMessage("Wrong password");
    } else {
      showLoginErrorMessage("Access denied");
    }
  }

  function getUnapproved() {
    $.getJSON('/unapproved', {
      sessionID : sid
    }, function(data) {
      if (data.modules) {
        $('#unapprovedModules').empty();
        $('#unapprovedModules').append(getLiFromMods(data.modules));
        appendButtons('#unapprovedModules');
        hideLoginForm();
        displayHideLink();
      } else if (data.status == 'denied') {
        showLoginErrorMessage("Bad session or session expired");
      } else {
        showLoginErrorMessage("Internal error");
      }
    })
  }

  function appendButtons(container) {
    $(container + ' .mod')
        .append(
            '<div class="buttons"><a class="approve" href="#"><img  src="images/approve.png" title="Approve" alt="Approve" /></a><a class="deny" href="#"><img title="Deny"  src="images/deny.png" alt="Deny" /></a></div>')

    $(container + ' .buttons .approve').attr('href', function(index, oldAttr) {
      return '#' + $(this).parents('.mod').attr('id');
    }).click(function(event) {
      event.preventDefault();

      var mod = $(this).attr('href');
      var modId = $(mod).attr('id');

      $("#dialogConfirmApprove").dialog({
        resizable : false,
        modal : true,
        buttons : {
          "Yes, I'm sure!" : function() {
            $(this).dialog("close");
            approve(modId);
          },
          Cancel : function() {
            $(this).dialog("close");
          }
        }
      });
    });

    $(container + ' .buttons .deny').attr('href', function(index, oldAttr) {
      return '#' + $(this).parents('.mod').attr('id');
    }).click(function(event) {
      event.preventDefault();

      var mod = $(this).attr('href');
      var modId = $(mod).attr('id');

      $("#dialogConfirmDeny").dialog({
        resizable : false,
        modal : true,
        buttons : {
          "Yes, I'm sure!" : function() {
            $(this).dialog("close");
            deny(modId);
          },
          Cancel : function() {
            $(this).dialog("close");
          }
        }
      });
    });
  }

  function deny(modId) {
    alert("For now, just assume the mod has been denied!")
    $('#' + modId).fadeOut(1000, function() {
      $(this).remove();
      fillLatestApprovedMods();
    })

    // $.post('/deny', {
    // sessionID : sid,
    // _id : modId
    // }, function(data) {
    // processDenyResult(data)
    // }, 'json');
  }

  function processApproveResult(json) {

    // TODO
  }

  function approve(modId) {
    $.post('/approve', {
      sessionID : sid,
      _id : modId
    }, function(data) {
      processApproveResult(data)
    }, 'json');
  }

  function processApproveResult(json) {
    if (json.status == 'ok')
      $('#' + json._id).fadeOut(1000, function() {
        $(this).remove();
        fillLatestApprovedMods();
      })
  }

  function displayHideLink() {
    $('a.showUnapproved').replaceWith('<a class="hideUnapproved href-less">[Hide]</a>')
    $('a.hideUnapproved').click(function() {
      $('#unapprovedModules').empty();
      displayShowLink();
    });
  }

  function displayShowLink() {
    $('a.hideUnapproved').replaceWith('<a class="showUnapproved href-less">[Show]</a>')
    $('a.showUnapproved').click(showUnapproved);
  }

  function processStatusError(data) {
    var s = 'Missing password';
    var messages = data.messages;
    if (messages.length > 0) {
      s = messages.pop();
      while (messages.length)
        s += '<br />' + messages.pop();
    }
    showLoginErrorMessage(s);
  }
}

function generateName() {
  var nameA = [ "awesome", "amazing", "fantastic", "marvelous", "storming", "staggering",
      "exciting", "mind-blowing", "astonishing", "handsome", "beautiful", "admirable", "lovely",
      "gorgeous", "exceptional", "uncommon", "terribly nice", "outstanding", "fine-looking",
      "well-favored", "glorious", "pulchritudinous", "ravishing", "stunning", "dazzling",
      "mind-boggling", "attractive", "graceful", "pleasing", "charismatic", "enchanting" ];
  var nameB = [ "men", "guys", "dudes", "fellows", "jossers", "wallahs", "blokes", "fellas",
      "fellers", "chaps", "lads", "people", "humans", "geezers", "boys", "gorillas", "bananas",
      "champs", "rockers", "pals" ];
  var a = Math.floor(Math.random() * nameA.length);
  var b = Math.floor(Math.random() * nameB.length);
  var name = nameA[a] + ' ' + nameB[b];
  return name;
}

function fillName() {
  $('#footer .randomName').text(generateName());
}

$(document).ready(function() {
  createRegisterFormHandler();
  createUnapprovedModsHandler();
  fillLatestApprovedMods();
  createSearchFormSubmitHandler();
  fillName();
});