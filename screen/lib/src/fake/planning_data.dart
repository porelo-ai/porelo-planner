import 'dart:convert';

/// A fresh board for the interactive mock and scenarios to change independently.
Map<String, dynamic> mockPlanningBoard() => jsonDecode(r'''
{
  "states": [
    {
      "name": "New",
      "kind": "WAITING",
      "measured": false
    },
    {
      "name": "Accepted",
      "kind": "WAITING",
      "measured": false
    },
    {
      "name": "In analysis",
      "kind": "WORKING",
      "measured": false
    },
    {
      "name": "Analyzed",
      "kind": "WAITING",
      "measured": false
    },
    {
      "name": "Under estimation",
      "kind": "WORKING",
      "measured": false
    },
    {
      "name": "Estimated",
      "kind": "WAITING",
      "measured": false
    },
    {
      "name": "Backlog",
      "kind": "WAITING",
      "measured": false
    },
    {
      "name": "Implementing",
      "kind": "WORKING",
      "measured": true
    },
    {
      "name": "Reading",
      "kind": "WORKING",
      "measured": true
    },
    {
      "name": "Review",
      "kind": "WAITING",
      "measured": true
    },
    {
      "name": "Rework",
      "kind": "WAITING",
      "measured": false
    },
    {
      "name": "Reworking",
      "kind": "WORKING",
      "measured": true
    },
    {
      "name": "Finishing",
      "kind": "WORKING",
      "measured": true
    },
    {
      "name": "Finished",
      "kind": "CLOSED",
      "measured": true
    },
    {
      "name": "Rejected",
      "kind": "CLOSED",
      "measured": false
    }
  ],
  "phases": [
    {
      "name": "Ideas",
      "states": [
        "New",
        "Accepted"
      ]
    },
    {
      "name": "Analysis",
      "states": [
        "In analysis",
        "Analyzed"
      ]
    },
    {
      "name": "Estimation",
      "states": [
        "Under estimation",
        "Estimated"
      ]
    },
    {
      "name": "Backlog",
      "states": [
        "Backlog"
      ]
    },
    {
      "name": "Build",
      "states": [
        "Implementing",
        "Reworking",
        "Reading",
        "Review",
        "Rework"
      ]
    },
    {
      "name": "Done",
      "states": [
        "Finishing",
        "Finished",
        "Rejected"
      ]
    }
  ],
  "issues": [
    {
      "id": "B900",
      "repository": "porelo",
      "title": "Planning work in no story",
      "state": "Backlog",
      "size": "S",
      "priority": "Medium",
      "blockedBy": [],
      "waitsOn": [],
      "parent": null,
      "link": "https://example.org/issues/B900",
      "text": "**What must be true.** The work meets the issue description.\n",
      "whatMustBeTrue": "The work meets the issue description.",
      "subIssues": [],
      "worker": "AGENT",
      "atWork": false,
      "needsYou": false,
      "readingStopped": false,
      "noRunHere": false,
      "attention": "NONE"
    }
  ],
  "stories": [
    {
      "id": "ST294",
      "title": "Plan the stories on a board in the window",
      "state": "In analysis",
      "priority": "High",
      "link": "https://example.org/stories/ST294",
      "issues": [],
      "work": {
        "run": "00000000-0000-4000-a000-000000000001",
        "tasks": [],
        "atWork": true,
        "since": "11:02",
        "coordinator": null,
        "repositories": [],
        "comment": "",
        "readingRounds": 2,
        "issueReadingRounds": [],
        "issues": []
      },
      "description": "The operator sees every story by state, the most important first, opens a story or an issue, and changes a story’s state by drag and drop, read from and written through a story API whose states are configured, not built in; a state change calls an action API.\n\nOpen Planning, find each story in its state’s column sorted by priority, open a story and one of its issues, drag a story to another state and save; the change is in porelo-project’s checkout. Rename a state in the configuration and the board shows it."
    },
    {
      "id": "ST72",
      "title": "A forge’s calls in a client that knows nothing of Porelo",
      "state": "Backlog",
      "priority": "Medium",
      "link": "https://example.org/stories/ST72",
      "issues": [
        {
          "id": "PJ74",
          "repository": "porelo-project",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/PJ74",
          "title": "Planning work",
          "size": "M",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "FC03",
          "repository": "porelo-forge-clients",
          "state": "Under estimation",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/FC03",
          "title": "Planning work",
          "size": "M",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "MANUAL",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "FC09",
          "repository": "porelo-forge-clients",
          "state": "Under estimation",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/FC09",
          "title": "Planning work",
          "size": "M",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "FC10",
          "repository": "porelo-forge-clients",
          "state": "Under estimation",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/FC10",
          "title": "Planning work",
          "size": "M",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "FC11",
          "repository": "porelo-forge-clients",
          "state": "Under estimation",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/FC11",
          "title": "Planning work",
          "size": "M",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "FC13",
          "repository": "porelo-forge-clients",
          "state": "Under estimation",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/FC13",
          "title": "Planning work",
          "size": "M",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        }
      ],
      "work": {
        "run": "00000000-0000-4000-a000-000000000002",
        "tasks": [],
        "atWork": true,
        "coordinator": null,
        "repositories": [],
        "comment": "",
        "readingRounds": 2,
        "issueReadingRounds": [],
        "issues": []
      },
      "description": "",
      "warnings": [
        {
          "kind": "IssueBehindStory",
          "text": "Its issues FC03, FC09, FC10, FC11 and FC13 stand before its state Backlog.",
          "holds": false
        }
      ]
    },
    {
      "id": "ST21",
      "title": "No channel file and no hand-over directory any more",
      "state": "New",
      "priority": "Highest",
      "link": "https://example.org/stories/ST21",
      "issues": [],
      "description": "",
      "work": null
    },
    {
      "id": "ST11",
      "title": "porelo-adapters and porelo-ai.github.io each in its own task",
      "state": "New",
      "priority": "Highest",
      "link": "https://example.org/stories/ST11",
      "description": "",
      "issues": [],
      "work": null
    },
    {
      "id": "ST13",
      "title": "porelo and porelo-host each in its task, with a way back",
      "state": "New",
      "priority": "Highest",
      "link": "https://example.org/stories/ST13",
      "description": "",
      "issues": [],
      "work": null
    },
    {
      "id": "ST28",
      "title": "The setup, move and recovery proven together",
      "state": "New",
      "priority": "Highest",
      "link": "https://example.org/stories/ST28",
      "issues": [],
      "description": "",
      "work": null
    },
    {
      "id": "ST87",
      "title": "Invalid machine inputs refused before renting",
      "state": "New",
      "priority": "Medium",
      "link": "https://example.org/stories/ST87",
      "description": "",
      "issues": [],
      "work": null
    },
    {
      "id": "ST182",
      "title": "Review a Bitbucket pull request",
      "state": "New",
      "priority": "Low",
      "link": "https://example.org/stories/ST182",
      "issues": [],
      "description": "",
      "work": null
    },
    {
      "id": "ST114",
      "title": "A vulnerability has a way in and is answered",
      "state": "Accepted",
      "priority": "Medium",
      "link": "https://example.org/stories/ST114",
      "description": "",
      "issues": [],
      "work": null
    },
    {
      "id": "ST111",
      "title": "Install from the intended package source",
      "state": "Accepted",
      "priority": "Medium",
      "link": "https://example.org/stories/ST111",
      "description": "",
      "issues": [],
      "work": null
    },
    {
      "id": "ST144",
      "title": "Understand where a binding setting came from",
      "state": "Accepted",
      "priority": "Low",
      "link": "https://example.org/stories/ST144",
      "description": "",
      "issues": [],
      "work": null
    },
    {
      "id": "ST14",
      "title": "The coordinator reads everything and changes only porelo-project",
      "state": "Analyzed",
      "priority": "Highest",
      "link": "https://example.org/stories/ST14",
      "issues": [
        {
          "id": "PJ58",
          "repository": "porelo-project",
          "state": "Analyzed",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/PJ58",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        }
      ],
      "description": "",
      "work": null
    },
    {
      "id": "ST39",
      "title": "A failed delivery named and kept visible",
      "state": "Analyzed",
      "priority": "High",
      "link": "https://example.org/stories/ST39",
      "description": "",
      "issues": [],
      "work": null
    },
    {
      "id": "ST35",
      "title": "Refuse an incompatible protocol and a meaningless option",
      "state": "Analyzed",
      "priority": "High",
      "link": "https://example.org/stories/ST35",
      "description": "",
      "issues": [],
      "work": null
    },
    {
      "id": "ST15",
      "title": "The website builds in its task",
      "state": "Estimated",
      "priority": "Highest",
      "link": "https://example.org/stories/ST15",
      "issues": [
        {
          "id": "SI05",
          "repository": "porelo-ai.github.io",
          "state": "Estimated",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/SI05",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        }
      ],
      "description": "",
      "work": null
    },
    {
      "id": "ST106",
      "title": "Make security failures hold the release",
      "state": "Estimated",
      "priority": "Medium",
      "link": "https://example.org/stories/ST106",
      "description": "",
      "issues": [],
      "work": null
    },
    {
      "id": "ST198",
      "title": "Reach a remote machine from Windows",
      "state": "Estimated",
      "priority": "Low",
      "link": "https://example.org/stories/ST198",
      "description": "",
      "issues": [],
      "work": null
    },
    {
      "id": "ST291",
      "title": "The host run by a program of its own, built like porelo",
      "state": "Implementing",
      "priority": "Highest",
      "link": "https://example.org/stories/ST291",
      "issues": [
        {
          "id": "B540",
          "repository": "porelo",
          "state": "Implementing",
          "title": "The host tool as a module of porelo",
          "size": "M",
          "priority": "Highest",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B540",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": true,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "B541",
          "whatMustBeTrue": "The work meets the story description.",
          "findings": [
            {
              "number": 1,
              "severity": "BLOCKING",
              "text": "Check the signature before using the record.",
              "file": "src/records.dart",
              "line": 42
            },
            {
              "number": 2,
              "severity": "NOTE",
              "text": "Keep the explanation beside the result."
            }
          ],
          "subAgents": [],
          "repository": "porelo",
          "state": "Implementing",
          "title": "The host tool reads the organization and a task by its signature",
          "size": "L",
          "priority": "Highest",
          "blockedBy": [
            "B540"
          ],
          "waitsOn": [],
          "link": "https://example.org/issues/B541",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": true,
          "attention": "NONE",
          "worker": "AGENT"
        },
        {
          "id": "B542",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B542",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "B543",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B543",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "B544",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B544",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "B545",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B545",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "B546",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B546",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "B547",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B547",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "B548",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B548",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "B549",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B549",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "B550",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B550",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "B551",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B551",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "B552",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B552",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "HO156",
          "repository": "porelo-host",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/HO156",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "HO157",
          "repository": "porelo-host",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/HO157",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "B540.1",
          "repository": "porelo",
          "state": "Backlog",
          "title": "The host tool's module builds in the porelo build",
          "size": "S",
          "priority": "Highest",
          "blockedBy": [],
          "waitsOn": [],
          "parent": "B540",
          "link": "https://example.org/issues/B540.1",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        }
      ],
      "description": "# The host tool\n\nThe host runs through a packaged native program.\n\nThe host is run by porelo-host, a native program packaged like porelo, instead of scripts copied onto it.\n\nRent a host, bring it up with porelo-host and run a move on it; every verb answers as the old scripts did.",
      "work": null,
      "warnings": [
        {
          "kind": "IssueBehindStory",
          "text": "Its issues B542, B543, B544, B545, B546, B547, B548, B549, B550, B551, B552, HO156, HO157 and B540.1 stand before its state Implementing.",
          "holds": false
        }
      ]
    },
    {
      "id": "ST292",
      "title": "A whole move rehearsed by the agents on a rented server",
      "state": "Backlog",
      "priority": "Highest",
      "link": "https://example.org/stories/ST292",
      "issues": [
        {
          "id": "B553",
          "repository": "porelo",
          "state": "Backlog",
          "title": "A move runs without a terminal for a rehearsal",
          "size": "M",
          "priority": "Highest",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B553",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "B554",
          "repository": "porelo",
          "state": "Backlog",
          "title": "A forge token’s reach checked against what a move needs",
          "size": "S",
          "priority": "Highest",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B554",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "F444",
          "repository": "porelo-frontend",
          "state": "Backlog",
          "title": "A move driven through the window against a rented host",
          "size": "M",
          "priority": "Highest",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/F444",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "F445",
          "repository": "porelo-frontend",
          "state": "Backlog",
          "title": "What a rehearsal cannot drive in the window, stood in for",
          "size": "S",
          "priority": "Highest",
          "blockedBy": [
            "F444"
          ],
          "waitsOn": [],
          "link": "https://example.org/issues/F445",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "HO158",
          "repository": "porelo-host",
          "state": "Backlog",
          "title": "A move rehearsed on a rented server",
          "size": "L",
          "priority": "Highest",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/HO158",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "MX89",
          "repository": "porelo-message-matrix",
          "state": "Backlog",
          "title": "A test person signs in through the gate unattended",
          "size": "S",
          "priority": "Highest",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/MX89",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "MX90",
          "repository": "porelo-message-matrix",
          "state": "Backlog",
          "title": "The room’s scenarios are rehearsed on a host",
          "size": "S",
          "priority": "Highest",
          "blockedBy": [
            "MX89"
          ],
          "waitsOn": [],
          "link": "https://example.org/issues/MX90",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "MX91",
          "repository": "porelo-message-matrix",
          "state": "Backlog",
          "title": "The rehearsal says a wake was answered",
          "size": "XS",
          "priority": "Highest",
          "blockedBy": [
            "MX89"
          ],
          "waitsOn": [],
          "link": "https://example.org/issues/MX91",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        }
      ],
      "description": "The agents rehearse a whole move on a rented server before the operator’s own test, so most problems are found earlier.\n\nRent a server and let the agents run the rehearsal; the report names every scenario OK, and the window and the room agree with the host.",
      "work": null
    },
    {
      "id": "ST293",
      "title": "Every agent works in a Porelo task, moved one at a time",
      "state": "Backlog",
      "priority": "Highest",
      "link": "https://example.org/stories/ST293",
      "issues": [],
      "description": "",
      "work": null
    },
    {
      "id": "ST23",
      "title": "The room and the usage records outlive the host",
      "state": "Backlog",
      "priority": "Highest",
      "link": "https://example.org/stories/ST23",
      "issues": [
        {
          "id": "MX24",
          "repository": "porelo-message-matrix",
          "state": "Review",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/MX24",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "MX36",
          "repository": "porelo-message-matrix",
          "state": "Finished",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/MX36",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "MX39",
          "repository": "porelo-message-matrix",
          "state": "Finished",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/MX39",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "HO07",
          "repository": "porelo-host",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/HO07",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "MANUAL",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "B231",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B231",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        }
      ],
      "description": "",
      "work": null
    },
    {
      "id": "ST6",
      "title": "porelo-agy and porelo-codex each in its own task",
      "state": "Backlog",
      "priority": "Highest",
      "link": "https://example.org/stories/ST6",
      "description": "",
      "issues": [],
      "work": null
    },
    {
      "id": "ST27",
      "title": "Every workflow runs on the local forge as on the central one",
      "state": "Implementing",
      "priority": "Highest",
      "link": "https://example.org/stories/ST27",
      "issues": [
        {
          "id": "IJ14",
          "repository": "porelo-intellij",
          "state": "Implementing",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/IJ14",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        }
      ],
      "work": {
        "run": "00000000-0000-4000-a000-000000000003",
        "tasks": [
          {
            "task": "build-one",
            "containerId": "mock-build-one",
            "machine": null,
            "repository": "",
            "issues": [],
            "state": "Implementing",
            "done": "Review",
            "mode": "UNATTENDED",
            "agent": "",
            "model": "",
            "role": "MAIN",
            "since": "09:10",
            "atWork": true,
            "ended": null
          },
          {
            "task": "build-two",
            "containerId": "mock-build-two",
            "machine": null,
            "repository": "",
            "issues": [],
            "state": "Implementing",
            "done": "Review",
            "mode": "UNATTENDED",
            "agent": "",
            "model": "",
            "role": "MAIN",
            "since": "09:10",
            "atWork": true,
            "ended": null
          }
        ],
        "atWork": true,
        "coordinator": null,
        "since": "09:10",
        "repositories": [],
        "comment": "",
        "readingRounds": 2,
        "issueReadingRounds": [],
        "issues": []
      },
      "description": ""
    },
    {
      "id": "ST12",
      "title": "The release and pipeline tools the move rests on",
      "state": "Implementing",
      "priority": "Highest",
      "link": "https://example.org/stories/ST12",
      "issues": [
        {
          "id": "BT35",
          "repository": "porelo-buildtools",
          "state": "Finished",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/BT35",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "BT36",
          "repository": "porelo-buildtools",
          "state": "Review",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/BT36",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "BT38",
          "repository": "porelo-buildtools",
          "state": "Review",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/BT38",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "BT43",
          "repository": "porelo-buildtools",
          "state": "Implementing",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/BT43",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "BT44",
          "repository": "porelo-buildtools",
          "state": "Implementing",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/BT44",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "BT45",
          "repository": "porelo-buildtools",
          "state": "Implementing",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/BT45",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "BT46",
          "repository": "porelo-buildtools",
          "state": "Implementing",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/BT46",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        }
      ],
      "work": {
        "run": "00000000-0000-4000-a000-000000000004",
        "tasks": [
          {
            "task": "build-one",
            "containerId": "mock-build-one",
            "machine": null,
            "repository": "",
            "issues": [],
            "state": "Implementing",
            "done": "Review",
            "mode": "UNATTENDED",
            "agent": "",
            "model": "",
            "role": "MAIN",
            "since": "09:10",
            "atWork": true,
            "ended": null
          },
          {
            "task": "build-two",
            "containerId": "mock-build-two",
            "machine": null,
            "repository": "",
            "issues": [],
            "state": "Implementing",
            "done": "Review",
            "mode": "UNATTENDED",
            "agent": "",
            "model": "",
            "role": "MAIN",
            "since": "09:10",
            "atWork": true,
            "ended": null
          }
        ],
        "atWork": true,
        "coordinator": null,
        "since": "09:10",
        "repositories": [],
        "comment": "",
        "readingRounds": 2,
        "issueReadingRounds": [],
        "issues": []
      },
      "description": ""
    },
    {
      "id": "ST5",
      "title": "porelo-pi, porelo-omp and porelo-claude-code each in its own task",
      "state": "Implementing",
      "priority": "Highest",
      "link": "https://example.org/stories/ST5",
      "issues": [
        {
          "id": "PI43",
          "repository": "porelo-pi",
          "state": "Implementing",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/PI43",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "OM44",
          "repository": "porelo-omp",
          "state": "Implementing",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/OM44",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "CC45",
          "repository": "porelo-claude-code",
          "state": "Implementing",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/CC45",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "PI39",
          "repository": "porelo-pi",
          "state": "Review",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/PI39",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "OM36",
          "repository": "porelo-omp",
          "state": "Review",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/OM36",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "CC39",
          "repository": "porelo-claude-code",
          "state": "Review",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/CC39",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        }
      ],
      "work": {
        "run": "00000000-0000-4000-a000-000000000005",
        "tasks": [
          {
            "task": "build-one",
            "containerId": "mock-build-one",
            "machine": null,
            "repository": "",
            "issues": [],
            "state": "Implementing",
            "done": "Review",
            "mode": "UNATTENDED",
            "agent": "",
            "model": "",
            "role": "MAIN",
            "since": "09:10",
            "atWork": true,
            "ended": null
          },
          {
            "task": "build-two",
            "containerId": "mock-build-two",
            "machine": null,
            "repository": "",
            "issues": [],
            "state": "Implementing",
            "done": "Review",
            "mode": "UNATTENDED",
            "agent": "",
            "model": "",
            "role": "MAIN",
            "since": "09:10",
            "atWork": true,
            "ended": null
          }
        ],
        "atWork": true,
        "coordinator": null,
        "since": "09:10",
        "repositories": [],
        "comment": "",
        "readingRounds": 2,
        "issueReadingRounds": [],
        "issues": []
      },
      "description": ""
    },
    {
      "id": "ST193",
      "title": "Choose drive areas for each host",
      "state": "Implementing",
      "priority": "Low",
      "link": "https://example.org/stories/ST193",
      "issues": [
        {
          "id": "PJ106",
          "repository": "porelo-project",
          "state": "Implementing",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/PJ106",
          "title": "Planning work",
          "size": "M",
          "priority": "High",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        }
      ],
      "work": {
        "run": "00000000-0000-4000-a000-000000000006",
        "tasks": [
          {
            "task": "build-one",
            "containerId": "mock-build-one",
            "machine": null,
            "repository": "",
            "issues": [],
            "state": "Implementing",
            "done": "Review",
            "mode": "UNATTENDED",
            "agent": "",
            "model": "",
            "role": "MAIN",
            "since": "09:10",
            "atWork": true,
            "ended": null
          },
          {
            "task": "build-two",
            "containerId": "mock-build-two",
            "machine": null,
            "repository": "",
            "issues": [],
            "state": "Implementing",
            "done": "Review",
            "mode": "UNATTENDED",
            "agent": "",
            "model": "",
            "role": "MAIN",
            "since": "09:10",
            "atWork": true,
            "ended": null
          }
        ],
        "atWork": true,
        "coordinator": null,
        "since": "09:10",
        "repositories": [],
        "comment": "",
        "readingRounds": 2,
        "issueReadingRounds": [],
        "issues": []
      },
      "description": ""
    },
    {
      "id": "ST127",
      "title": "Keep container limits on large hosts",
      "state": "Implementing",
      "priority": "Low",
      "link": "https://example.org/stories/ST127",
      "issues": [],
      "work": {
        "run": "00000000-0000-4000-a000-000000000007",
        "tasks": [
          {
            "task": "build-one",
            "containerId": "mock-build-one",
            "machine": null,
            "repository": "",
            "issues": [],
            "state": "Implementing",
            "done": "Review",
            "mode": "UNATTENDED",
            "agent": "",
            "model": "",
            "role": "MAIN",
            "since": "09:10",
            "atWork": true,
            "ended": null
          },
          {
            "task": "build-two",
            "containerId": "mock-build-two",
            "machine": null,
            "repository": "",
            "issues": [],
            "state": "Implementing",
            "done": "Review",
            "mode": "UNATTENDED",
            "agent": "",
            "model": "",
            "role": "MAIN",
            "since": "09:10",
            "atWork": true,
            "ended": null
          }
        ],
        "atWork": true,
        "coordinator": null,
        "since": "09:10",
        "repositories": [],
        "comment": "",
        "readingRounds": 2,
        "issueReadingRounds": [],
        "issues": []
      },
      "description": ""
    },
    {
      "id": "ST1",
      "title": "Builds on the host’s forge resolve snapshots with a read-only token",
      "state": "Review",
      "priority": "Highest",
      "link": "https://example.org/stories/ST1",
      "issues": [
        {
          "id": "BT42",
          "repository": "porelo-buildtools",
          "state": "Review",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/BT42",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "BT39",
          "repository": "porelo-buildtools",
          "state": "Review",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/BT39",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "HO52",
          "repository": "porelo-host",
          "state": "Review",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/HO52",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "SL36",
          "repository": "porelo-message-sluice",
          "state": "Review",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/SL36",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "BT40",
          "repository": "porelo-buildtools",
          "state": "Finished",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/BT40",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "BT41",
          "repository": "porelo-buildtools",
          "state": "Finished",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/BT41",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "HO53",
          "repository": "porelo-host",
          "state": "Finished",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/HO53",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        }
      ],
      "description": "",
      "work": null
    },
    {
      "id": "ST2",
      "title": "porelo-message-sluice works wholly in its task",
      "state": "Review",
      "priority": "Highest",
      "link": "https://example.org/stories/ST2",
      "issues": [
        {
          "id": "SL35",
          "repository": "porelo-message-sluice",
          "state": "Review",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/SL35",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "MANUAL",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        }
      ],
      "description": "",
      "work": null
    },
    {
      "id": "ST3",
      "title": "porelo’s Build on the host’s Forgejo",
      "state": "Review",
      "priority": "Highest",
      "link": "https://example.org/stories/ST3",
      "issues": [
        {
          "id": "B306",
          "repository": "porelo",
          "state": "Review",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B306",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        }
      ],
      "description": "",
      "work": null
    },
    {
      "id": "ST10",
      "title": "Test machines by kind from the VM pool",
      "state": "Review",
      "priority": "Highest",
      "link": "https://example.org/stories/ST10",
      "issues": [
        {
          "id": "HO12",
          "repository": "porelo-host",
          "state": "Review",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/HO12",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "BT24",
          "repository": "porelo-buildtools",
          "state": "Review",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/BT24",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "BT25",
          "repository": "porelo-buildtools",
          "state": "Review",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/BT25",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        }
      ],
      "description": "",
      "work": null
    },
    {
      "id": "ST295",
      "title": "Hand the sluice's last checks to a person",
      "state": "Backlog",
      "priority": "Low",
      "link": "https://example.org/stories/ST295",
      "issues": [
        {
          "id": "SL37",
          "repository": "porelo-message-sluice",
          "state": "Review",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/SL37",
          "title": "Planning work",
          "size": "S",
          "priority": "Low",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "SL38",
          "repository": "porelo-message-sluice",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/SL38",
          "title": "Planning work",
          "size": "S",
          "priority": "Low",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        }
      ],
      "description": "",
      "work": null
    },
    {
      "id": "ST264",
      "title": "A host prepared with no project or person",
      "state": "Finished",
      "priority": "Highest",
      "link": "https://example.org/stories/ST264",
      "description": "",
      "issues": [],
      "work": null
    },
    {
      "id": "ST268",
      "title": "Each person’s one key and card, kept in the registry",
      "state": "Finished",
      "priority": "Highest",
      "link": "https://example.org/stories/ST268",
      "description": "",
      "issues": [],
      "work": null
    },
    {
      "id": "ST243",
      "title": "Both adapters pass one contract suite",
      "state": "Finished",
      "priority": "Highest",
      "link": "https://example.org/stories/ST243",
      "issues": [
        {
          "id": "PJ62",
          "repository": "porelo-project",
          "state": "Finished",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/PJ62",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        },
        {
          "id": "GH18",
          "repository": "porelo-adapters",
          "state": "Finished",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/GH18",
          "title": "Planning work",
          "size": "M",
          "priority": "Highest",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the story description.",
          "subAgents": []
        }
      ],
      "description": "",
      "work": null
    },
    {
      "id": "ST296",
      "title": "Items the planner could not fully read",
      "state": "Backlog",
      "priority": "Medium",
      "link": "https://example.org/stories/ST296",
      "issues": [
        {
          "id": "B870",
          "repository": "porelo",
          "state": "Parked",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B870",
          "title": "An issue in a state outside the workflow",
          "size": "S",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the issue description.",
          "subAgents": [],
          "warnings": [
            {
              "kind": "StateOutsideWorkflow",
              "text": "Its state is none of the workflow's states, so no work starts on it.",
              "holds": true
            }
          ]
        },
        {
          "id": "B871",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B871",
          "title": "An issue whose priority the planner does not know",
          "size": "S",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the issue description.",
          "subAgents": [],
          "warnings": [
            {
              "kind": "UnknownPriority",
              "text": "Its priority is none the planner knows, so it is sorted as Medium.",
              "holds": false
            }
          ]
        },
        {
          "id": "B872",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B872",
          "title": "An issue whose id stands twice",
          "size": "S",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the issue description.",
          "subAgents": [],
          "warnings": [
            {
              "kind": "IdTwice",
              "text": "Its id names another item too, so it can be neither worked nor moved until one id is changed.",
              "holds": true
            }
          ]
        },
        {
          "id": "B872",
          "repository": "porelo-host",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B872",
          "title": "The other issue whose id stands twice",
          "size": "S",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the issue description.",
          "subAgents": [],
          "warnings": [
            {
              "kind": "IdTwice",
              "text": "Its id names another item too, so it can be neither worked nor moved until one id is changed.",
              "holds": true
            }
          ]
        },
        {
          "id": "B873",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B873",
          "title": "An issue whose parent is outside the project",
          "size": "S",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the issue description.",
          "subAgents": [],
          "warnings": [
            {
              "kind": "ParentOutsideProject",
              "text": "Its parent is outside the project and is not shown.",
              "holds": true
            }
          ]
        },
        {
          "id": "B874",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B874",
          "title": "An issue waiting on an issue outside the project",
          "size": "S",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the issue description.",
          "subAgents": [],
          "warnings": [
            {
              "kind": "BlockerOutsideProject",
              "text": "An issue it waits on is outside the project and is not shown.",
              "holds": true
            }
          ]
        },
        {
          "id": "B875",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B875",
          "title": "An issue under another parent than its id names",
          "size": "S",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the issue description.",
          "subAgents": [],
          "warnings": [
            {
              "kind": "WrongParent",
              "text": "It stands under another parent than its id names.",
              "holds": true
            }
          ]
        },
        {
          "id": "B876",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B876",
          "title": "An issue whose text has no sections",
          "size": "S",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the issue description.",
          "subAgents": [],
          "warnings": [
            {
              "kind": "BodyNotSectioned",
              "text": "Its text has no sections the planner can read; it is shown as it stands.",
              "holds": false
            }
          ]
        },
        {
          "id": "B877",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B877",
          "title": "An issue marked a story outside the stories' repository",
          "size": "S",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the issue description.",
          "subAgents": [],
          "warnings": [
            {
              "kind": "StoryOutsideOwnRepository",
              "text": "It is marked a story outside the repository that holds the stories, so it is shown as an issue and no work starts on it."
            }
          ]
        },
        {
          "id": "B878",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B878",
          "title": "An issue whose parent is in another repository",
          "size": "S",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the issue description.",
          "subAgents": [],
          "warnings": [
            {
              "kind": "ParentInOtherRepository",
              "text": "Its parent is in another repository, so it is shown without its parent."
            }
          ]
        },
        {
          "id": "B879",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B879",
          "title": "An issue blocked by a story",
          "size": "S",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the issue description.",
          "subAgents": [],
          "warnings": [
            {
              "kind": "BlockedByStory",
              "text": "It is blocked by a story, which only an issue can block, so it is shown without that blocker."
            }
          ]
        },
        {
          "id": "B880",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B880",
          "title": "An issue whose parents lead back to it",
          "size": "S",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the issue description.",
          "subAgents": [],
          "warnings": [
            {
              "kind": "ParentCircle",
              "text": "Its parents lead back to it, so it is shown without its parent."
            }
          ]
        },
        {
          "id": "B881",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B881",
          "title": "A sub-issue of a sub-issue",
          "size": "S",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the issue description.",
          "subAgents": [],
          "warnings": [
            {
              "kind": "SubIssueOfSubIssue",
              "text": "Its parent is a sub-issue itself, which the board cannot hold, so it is shown without its parent."
            }
          ]
        },
        {
          "id": "B882",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B882",
          "title": "An issue read past one page",
          "size": "S",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the issue description.",
          "subAgents": [],
          "warnings": [
            {
              "kind": "PastOnePage",
              "text": "It has more sub-issues, blockers, labels or field values than one page carries, so it is shown as far as it was read."
            }
          ]
        },
        {
          "id": "B883",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B883",
          "title": "An issue that hangs on one the board does not place",
          "size": "S",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the issue description.",
          "subAgents": [],
          "warnings": [
            {
              "kind": "DependsOnUnplaced",
              "text": "An issue it stands under or waits on is not placed on the board, so it is shown without that link."
            }
          ]
        },
        {
          "id": "B884",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B884",
          "title": "An issue whose text is longer than GitHub takes",
          "size": "S",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "subAgents": [],
          "warnings": [
            {
              "kind": "BodyTooLong",
              "text": "Its text is longer than GitHub takes, so it is shown without it."
            }
          ]
        },
        {
          "id": "B885",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B885",
          "title": "An issue whose size the planner does not know",
          "size": null,
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the issue description.",
          "subAgents": [],
          "warnings": [
            {
              "kind": "UnknownSize",
              "text": "Its size is none the planner knows, so it is shown without one."
            }
          ]
        },
        {
          "id": "B886",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B886",
          "title": "An issue whose number field holds no number",
          "size": "S",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the issue description.",
          "subAgents": [],
          "warnings": [
            {
              "kind": "UnreadableNumber",
              "text": "Its field Minutes holds no number in the range the planner reads, so the board counts it as none and saves no stage over it."
            }
          ]
        },
        {
          "id": "B887",
          "repository": "porelo",
          "state": "Rejected",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B887",
          "title": "An issue GitHub closed as not planned, whatever its Status reads",
          "size": "S",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the issue description.",
          "subAgents": []
        },
        {
          "id": "B888",
          "repository": "porelo",
          "state": "Finished",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B888",
          "title": "An issue GitHub closed as completed, whatever its Status reads",
          "size": "S",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the issue description.",
          "subAgents": []
        },
        {
          "id": "B889",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B889",
          "title": "An issue whose worker the planner does not know",
          "size": "S",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the issue description.",
          "subAgents": [],
          "warnings": [
            {
              "kind": "UnknownWorker",
              "text": "Its worker is none the planner knows, so no work starts on it until one it knows is set."
            }
          ]
        },
        {
          "id": "B890",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "link": "https://example.org/issues/B890",
          "title": "An issue whose risk the planner does not know",
          "size": "S",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the issue description.",
          "subAgents": [],
          "warnings": [
            {
              "kind": "UnknownRisk",
              "text": "Its risk is none the planner knows, so no work starts on it until one it knows is set."
            }
          ]
        },
        {
          "id": "B889.1",
          "repository": "porelo",
          "state": "Backlog",
          "blockedBy": [],
          "waitsOn": [],
          "parent": "B889",
          "link": "https://example.org/issues/B889.1",
          "title": "A part held with its parent, whose worker the planner does not know",
          "size": "S",
          "priority": "Medium",
          "rounds": 0,
          "roundsMost": 2,
          "atWork": false,
          "needsYou": false,
          "readingStopped": false,
          "noRunHere": false,
          "attention": "NONE",
          "worker": "AGENT",
          "findings": [],
          "whatMustBeTrue": "The work meets the issue description.",
          "subAgents": [],
          "warnings": []
        }
      ],
      "description": "",
      "work": null,
      "warnings": []
    },
    {
      "id": "ST297",
      "title": "A story whose state cannot be told",
      "state": "",
      "priority": "Medium",
      "link": "https://example.org/stories/ST297",
      "issues": [],
      "description": "",
      "work": null,
      "warnings": [
        {
          "kind": "NoState",
          "text": "No state can be told for it: it states none, and its issues give none.",
          "holds": true
        }
      ]
    },
    {
      "id": "ST298",
      "title": "A story naming an issue outside the project",
      "state": "New",
      "priority": "Medium",
      "link": "https://example.org/stories/ST298",
      "issues": [],
      "description": "",
      "work": null,
      "warnings": [
        {
          "kind": "IssueOutsideProject",
          "text": "It names an issue that is no issue of the project's repositories, which is not shown.",
          "holds": false
        }
      ]
    },
    {
      "id": "ST299",
      "title": "A story edited by hand where the planner cannot follow",
      "state": "New",
      "priority": "Medium",
      "link": "https://example.org/stories/ST299",
      "issues": [],
      "description": "",
      "work": null,
      "warnings": [
        {
          "kind": "StoryUnderIssue",
          "text": "It stands under an issue, which a story never does, so it is shown without it."
        },
        {
          "kind": "AlsoNeedsNotOnBoard",
          "text": "A line under Also needs names no issue the board shows, so it is shown without it."
        },
        {
          "kind": "NoPriority",
          "text": "It has no priority, so it is sorted as Medium."
        }
      ]
    }
  ],
  "revision": "design-1",
  "repositories": [
    "porelo-project",
    "porelo-forge-clients",
    "porelo-ai.github.io",
    "porelo",
    "porelo-host",
    "porelo-frontend",
    "porelo-message-matrix",
    "porelo-intellij",
    "porelo-buildtools",
    "porelo-pi",
    "porelo-omp",
    "porelo-claude-code",
    "porelo-message-sluice",
    "porelo-adapters"
  ],
  "work": [
    {
      "state": "In analysis",
      "from": [
        "New",
        "Accepted",
        "Analyzed",
        "Estimated",
        "Backlog",
        "Review",
        "Rework"
      ],
      "done": "Review",
      "then": null,
      "blocked": "Reworking",
      "per": "REPOSITORY",
      "modes": [
        "UNATTENDED",
        "AGENT"
      ],
      "order": "Read the story and complete its work.",
      "models": {
        "claude": "claude-opus-5-5"
      }
    },
    {
      "state": "Under estimation",
      "from": [
        "New",
        "Accepted",
        "Analyzed",
        "Estimated",
        "Backlog",
        "Review",
        "Rework"
      ],
      "done": "Review",
      "then": null,
      "blocked": "Reworking",
      "per": "REPOSITORY",
      "modes": [
        "UNATTENDED",
        "AGENT"
      ],
      "order": "Read the story and complete its work.",
      "models": {
        "claude": "claude-opus-5-5"
      }
    },
    {
      "state": "Implementing",
      "from": [
        "New",
        "Accepted",
        "Analyzed",
        "Estimated",
        "Backlog",
        "Review",
        "Rework"
      ],
      "done": "Review",
      "then": null,
      "blocked": "Reworking",
      "per": "REPOSITORY",
      "modes": [
        "UNATTENDED",
        "AGENT"
      ],
      "order": "Read the story and complete its work.",
      "models": {
        "claude": "claude-opus-5-5"
      }
    },
    {
      "state": "Reading",
      "from": [
        "New",
        "Accepted",
        "Analyzed",
        "Estimated",
        "Backlog",
        "Review",
        "Rework"
      ],
      "done": "Review",
      "then": null,
      "blocked": "Reworking",
      "per": "REPOSITORY",
      "modes": [
        "UNATTENDED",
        "AGENT"
      ],
      "order": "Read the story and complete its work.",
      "models": {
        "claude": "claude-opus-5-5"
      }
    },
    {
      "state": "Reworking",
      "from": [
        "New",
        "Accepted",
        "Analyzed",
        "Estimated",
        "Backlog",
        "Review",
        "Rework"
      ],
      "done": "Review",
      "then": null,
      "blocked": "Reworking",
      "per": "REPOSITORY",
      "modes": [
        "UNATTENDED",
        "AGENT"
      ],
      "order": "Read the story and complete its work.",
      "models": {
        "claude": "claude-opus-5-5"
      }
    },
    {
      "state": "Finishing",
      "from": [
        "New",
        "Accepted",
        "Analyzed",
        "Estimated",
        "Backlog",
        "Review",
        "Rework"
      ],
      "done": "Review",
      "then": null,
      "blocked": "Reworking",
      "per": "REPOSITORY",
      "modes": [
        "UNATTENDED",
        "AGENT"
      ],
      "order": "Read the story and complete its work.",
      "models": {
        "claude": "claude-opus-5-5"
      }
    }
  ],
  "faults": [],
  "reasons": true,
  "rules": [
    {
      "item": "ST193",
      "rule": "StoryPriorityBelowIssue",
      "words": "ST193 has a lower priority than its open issue PJ106."
    },
    {
      "item": "B900",
      "rule": "IssueInNoStory",
      "words": "B900 stands past the workflow's first state in no story."
    }
  ]
}
''') as Map<String, dynamic>;

/// Finds exactly one reported story for stand-in detail reads.
Map<String, dynamic>? mockPlanningStory(Map<String, dynamic> board, Object? id) {
  final stories = board['stories'];
  if (id is! String || id.isEmpty || stories is! List) return null;
  return stories.whereType<Map<String, dynamic>>().where((story) => story['id'] == id).firstOrNull;
}

/// Finds an issue and supplies its owning story from the stand-in store.
Map<String, dynamic>? mockPlanningIssue(Map<String, dynamic> board, Object? id) {
  final stories = board['stories'];
  if (id is! String || id.isEmpty || stories is! List) return null;
  for (final story in stories.whereType<Map<String, dynamic>>()) {
    final issues = story['issues'];
    if (issues is! List) continue;
    final issue = issues.whereType<Map<String, dynamic>>().where((issue) => issue['id'] == id).firstOrNull;
    if (issue != null) return Map<String, dynamic>.of(issue);
  }
  return (board['issues'] as List? ?? const [])
      .whereType<Map<String, dynamic>>()
      .where((issue) => issue['id'] == id)
      .firstOrNull;
}

/// Keeps story issues and all their descendants out of the standalone list, as the planner does.
List<Map<String, dynamic>> mockPlanningNoStoryIssues(Map<String, dynamic> board) {
  final issues = (board['issues'] as List? ?? const []).whereType<Map<String, dynamic>>().toList();
  final held = {
    for (final story in (board['stories'] as List).whereType<Map<String, dynamic>>())
      for (final issue in (story['issues'] as List? ?? const []).whereType<Map<String, dynamic>>()) issue['id'],
  };
  var changed = true;
  while (changed) {
    changed = false;
    for (final issue in issues) {
      if (held.contains(issue['parent']) && held.add(issue['id'])) changed = true;
    }
  }
  final states = [for (final state in (board['states'] as List).whereType<Map<String, dynamic>>()) state['name']];
  // As the planner answers: by the workflow's order of states, and one in a state the workflow lacks after them.
  return [
    for (final state in states)
      for (final issue in issues)
        if (!held.contains(issue['id']) && issue['state'] == state) _planningIssueSummary(issue),
    for (final issue in issues)
      if (!held.contains(issue['id']) && !states.contains(issue['state'])) _planningIssueSummary(issue),
  ];
}

/// A stand-in's digest: 64 hexadecimal characters that differ with [text].
String mockDigest(String text) {
  var hash = 0xcbf29ce484222325;
  final out = StringBuffer();
  for (var round = 0; out.length < 64; round++) {
    for (final unit in '$round$text'.codeUnits) {
      hash = ((hash ^ unit) * 0x100000001b3) & 0xFFFFFFFFFFFF;
    }
    out.write(hash.toRadixString(16).padLeft(12, '0'));
  }
  return out.toString().substring(0, 64);
}

/// The version a stand-in gives an item, over its own fields as the planner's changes with them: a story's issues
/// count by their ids, a version it carried not at all.
String mockItemVersion(Map<String, dynamic> item) {
  final own = Map<String, dynamic>.of(item)..remove('version');
  if (own['issues'] case final List<dynamic> issues) {
    own['issues'] = [for (final issue in issues) issue is Map ? issue['id'] : issue];
  }
  return mockDigest(jsonEncode(own));
}

/// Each item [board] holds by id with its version: its stories, their issues, the issues in no story and those made
/// outside the project; the first copy of an id stands for it.
Map<String, String> mockItemVersions(Map<String, dynamic> board) {
  final versions = <String, String>{};
  void add(Object? item) {
    if (item is Map<String, dynamic> && item['id'] is String) {
      versions.putIfAbsent(item['id'] as String, () => mockItemVersion(item));
    }
  }

  for (final story in (board['stories'] as List? ?? const [])) {
    add(story);
    if (story is Map<String, dynamic>) (story['issues'] as List? ?? const []).forEach(add);
  }
  (board['issues'] as List? ?? const []).forEach(add);
  (board['unlisted'] as List? ?? const []).forEach(add);
  return versions;
}

/// [board]'s stories as they are, each and each of its issues with the version it stands at.
List<Map<String, dynamic>> mockVersionedStories(Map<String, dynamic> board) => [
  for (final story in (board['stories'] as List).cast<Map<String, dynamic>>())
    {
      ...story,
      'version': mockItemVersion(story),
      if (story['issues'] case final List<dynamic> issues)
        'issues': [
          for (final issue in issues)
            if (issue is Map<String, dynamic>) {...issue, 'version': mockItemVersion(issue)} else issue,
        ],
    },
];

Map<String, dynamic> _planningIssueSummary(Map<String, dynamic> issue) => Map<String, dynamic>.of(issue)
  ..['version'] = mockItemVersion(issue)
  ..remove('text')
  ..remove('textFormat')
  ..remove('whatMustBeTrue')
  ..remove('subAgents')
  ..remove('subIssues');

/// Compact summaries omit fields belonging only to the issue's own page.
Map<String, dynamic> planningSummary(Map<String, dynamic> board) => {
  'stories': [
    for (final story in (board['stories'] as List).cast<Map<String, dynamic>>())
      {
        ...story,
        'version': mockItemVersion(story),
        'description': '',
        if (story['issues'] case final List<dynamic> issues)
          'issues': [for (final issue in issues.cast<Map<String, dynamic>>()) _planningIssueSummary(issue)],
      },
  ],
  'revision': board['revision'],
  if (board['open'] case final Map<String, dynamic> open) 'open': open,
  if (board['repositories'] case final List<dynamic> repositories) 'repositories': repositories,
  // A planner's event is a whole board, so the screen takes changed rules from it.
  for (final part in const ['states', 'phases', 'work', 'faults', 'rules'])
    if (board[part] case final List<dynamic> value) part: value,
  if (board['unlisted'] case final List<dynamic> unlisted)
    'unlisted': [
      for (final item in unlisted)
        if (item is Map<String, dynamic>) {...item, 'version': mockItemVersion(item)} else item,
    ],
  if (board['reasons'] case final bool reasons) 'reasons': reasons,
};
