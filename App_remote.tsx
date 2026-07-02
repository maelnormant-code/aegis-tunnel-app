import React, { useState, useEffect, useRef } from "react";
import { 
  Shield, 
  Terminal, 
  Activity, 
  Cpu, 
  RefreshCw, 
  Smartphone, 
  Lock, 
  Network, 
  Database, 
  ArrowRight, 
  FileText, 
  CheckCircle, 
  AlertTriangle, 
  Send, 
  BookOpen, 
  Check, 
  Copy,
  ChevronRight,
  Layers,
  Search,
  Sliders,
  Radio,
  Eye,
  Key
} from "lucide-react";

// Pre-packaged DevLog content from /components/qubes-aegis/DEVLOG.md
const DEVLOG_CONTENT = `
# Qubes Aegis - Development Log

## Overview
This project implements the **Qubes Aegis** architecture—an AI-driven copilot and system management suite for Qubes OS, enabling autonomous security, automated documentation, and AI-assisted VM management with strict compartmentalization.

## Recent Updates & Advanced Features

### Aegis Tunnel App Server-Side Peer (sys-vpn)
- Implemented the server-side peer component inside \`sys-vpn\` (a designated gateway VM) to connect the \`aegis-tunnel-app\` on mobile devices with the offline desktop Aegis ecosystem.
- Implemented \`aegis-tunnel-proxy.py\`, an HTTP API server running on TCP port \`5000\` inside \`sys-vpn\` to accept client requests over the WireGuard tunnel and safely bridge them to \`sys-copilot\` via secure Qubes RPC (\`aegis.HeimdallChat\`).
- Added the \`aegis.HeimdallChat\` qrexec RPC handler and policy in \`sys-copilot\` and \`dom0\` respectively, which securely forwards payloads to the running Heimdall Agent loop via \`/run/aegis/heimdall.sock\`.
- Implemented a Unix domain socket server in \`heimdall-agent.py\` to allow multi-tenant socket connections (local console, qrexec RPC handlers) without breaking backwards compatibility with stdin loops.
- Drafted the SaltStack formula \`sys-vpn.sls\` to automatically provision \`sys-vpn\`, enable gateway capabilities (\`provides_network\`), and assign the \`aegis-guest\` security tag.
- Created a dedicated \`qubes-aegis-sys-vpn.spec\` to build \`qubes-aegis-sys-vpn\` RPMs and added it to the global \`Makefile.builder\`.

### Autonomous Goal Execution Loop (The /goal directive)
- Implemented a \`/goal\` directive in \`heimdall-agent.py\` to enable persistent, autonomous task completion.
- Introduced a "Builder and Judge" dual-prompt architecture: the agent attempts to fulfill the task, and a meta-evaluating judge LLM verifies if the criteria are entirely satisfied. If incomplete, the judge generates feedback and forces the agent to retry until successful (up to a bounded iteration limit of 5 turns).

### Anti-Censorship & Darknet Integration
- Implemented \`deploy_darknet_scout\` in \`heimdall_tools.py\` to allow the deployment of autonomous subagents that route exclusively through darknets (Tor/sys-whonix, I2P) to bypass clearweb policing.
`;

const VM_NODES = [
  {
    id: "dom0",
    name: "Dom0 (Admin VM)",
    type: "Admin",
    status: "Secure",
    color: "text-red-400 bg-red-950/40 border-red-800/60",
    description: "The host administrative domain. Enforces Qrexec policies and coordinates SaltStack deployments.",
    rpms: ["qubes-aegis-dom0-1.0.0"],
    salt: "30-aegis.policy"
  },
  {
    id: "sys-copilot",
    name: "sys-copilot",
    type: "Offline Intelligence",
    status: "Online",
    color: "text-purple-400 bg-purple-950/40 border-purple-800/60",
    description: "Runs the Heimdall agent loop on `/run/aegis/heimdall.sock`. Isolated with NetVM: none.",
    rpms: ["qubes-aegis-sys-copilot-1.0.0"],
    salt: "sys-copilot.sls"
  },
  {
    id: "sys-vpn",
    name: "sys-vpn (WireGuard Server)",
    type: "Gateway & Connector",
    status: "Active (Connected)",
    color: "text-emerald-400 bg-emerald-950/40 border-emerald-800/60",
    description: "Terminates VPN from mobile phone over wg0 (10.137.0.1). Forwards chat queries via Qrexec and syncs vaults with Syncthing.",
    rpms: ["qubes-aegis-sys-vpn-1.0.0", "wireguard-tools", "syncthing"],
    salt: "sys-vpn.sls"
  },
  {
    id: "sys-ai",
    name: "sys-ai",
    type: "Inference Executor",
    status: "Online",
    color: "text-blue-400 bg-blue-950/40 border-blue-800/60",
    description: "Hosts the LLM llama-server socket under strict attestation. Quarantined from internet.",
    rpms: ["qubes-aegis-sys-ai-1.0.0"],
    salt: "sys-ai.sls"
  },
  {
    id: "sys-knowledge",
    name: "sys-knowledge",
    type: "Vector Store Memory",
    status: "Synced",
    color: "text-cyan-400 bg-cyan-950/40 border-cyan-800/60",
    description: "Contains RAG databases, verification hashes, and persistent ACS network graphs.",
    rpms: ["qubes-aegis-sys-knowledge-1.0.0"],
    salt: "sys-knowledge.sls"
  },
  {
    id: "sys-whonix",
    name: "sys-whonix",
    type: "Tor Gateway",
    status: "Active",
    color: "text-yellow-400 bg-yellow-950/40 border-yellow-800/60",
    description: "Bridges darknet scouts for secure packages download and anti-censorship communication.",
    rpms: ["qubes-core-agent-networking"],
    salt: "Default template"
  }
];

export default function App() {
  const [activeTab, setActiveTab] = useState<"topology" | "mobile" | "terminal" | "devlog">("topology");
  const [selectedNode, setSelectedNode] = useState<typeof VM_NODES[0]>(VM_NODES[2]); // Default to sys-vpn
  const [copiedKey, setCopiedKey] = useState(false);
  
  // Handshake log simulator
  const [handshakeLogs, setHandshakeLogs] = useState<string[]>([
    "Initializing sys-vpn gateway...",
    "WireGuard interface wg0 raised on 10.137.0.1:51820",
    "Aegis Tunnel Proxy daemon listening on 10.137.0.1:5000",
    "Syncthing engine started (Device ID: AEGIS-SYNC-VTX-77A-P9B)",
    "Ready for mobile peer handshake..."
  ]);
  const [isConnected, setIsConnected] = useState(false);

  // Terminal simulator state
  const [terminalInput, setTerminalInput] = useState("");
  const [terminalHistory, setTerminalHistory] = useState<Array<{ type: "user" | "thought" | "tool" | "judge" | "final" | "error"; text: string }>>([
    { type: "final", text: "Heimdall Admin Interface v1.0.0 initialized.\nConnected to sys-copilot via local UNIX socket: /run/aegis/heimdall.sock\nType a command or query to interact with the system." }
  ]);
  const [isProcessing, setIsProcessing] = useState(false);
  const terminalEndRef = useRef<HTMLDivElement>(null);

  // Auto scroll terminal
  useEffect(() => {
    terminalEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [terminalHistory]);

  // Handle simulated peer connection
  const triggerSimulatedHandshake = () => {
    if (isConnected) {
      setHandshakeLogs(prev => [
        ...prev,
        `[${new Date().toLocaleTimeString()}] Peer 10.137.0.2 requested disconnect.`,
        `[${new Date().toLocaleTimeString()}] Tunnel wg0 cleared.`
      ]);
      setIsConnected(false);
      return;
    }

    setIsConnected(true);
    const steps = [
      "Peer (10.137.0.2) UDP handshake received on port 51820",
      "WireGuard tunnel established successfully. MTU=1420",
      "Syncthing exchange: Device ID match verified for Phone-Client",
      "Syncthing: Folder 'vault' (/var/lib/aegis/vault/) sync initiated",
      "Syncthing: Sync complete. 'aegis_vault.kdbx' successfully mirrored",
      "Proxy API: Heartbeat request from phone client '/api/status' [HTTP 200]",
      "Aegis Secure Node connection ACTIVE."
    ];

    steps.forEach((step, idx) => {
      setTimeout(() => {
        setHandshakeLogs(prev => [...prev, `[${new Date().toLocaleTimeString()}] ${step}`]);
      }, (idx + 1) * 800);
    });
  };

  // Pre-canned terminal inputs
  const PRE_CANNED_QUERIES = [
    { label: "Query Context", query: "Get active security state and verify sys-vpn packages" },
    { label: "Setup Immich AppVM (Goal)", query: "/goal Set up Immich in a designated AppVM with external GPU acceleration" },
    { label: "Route Daemon", query: "Route Monero daemon as an anonymous .onion service via sys-whonix" }
  ];

  const handleTerminalSubmit = (queryText?: string) => {
    const query = queryText || terminalInput;
    if (!query.trim() || isProcessing) return;

    setTerminalHistory(prev => [...prev, { type: "user", text: query }]);
    if (!queryText) setTerminalInput("");
    setIsProcessing(true);

    // Simulate multi-step AI Agent thinking and tool calling
    setTimeout(() => {
      if (query.startsWith("/goal")) {
        // Run Judge and Builder Goal loop
        setTerminalHistory(prev => [...prev, 
          { type: "thought", text: "User initiated an absolute must-finish /goal request. I will initiate the Builder-Judge iterative reflection loop." },
          { type: "tool", text: "Applying SaltState: qubes.ApplyAISystemState (Target VM: app-immich-vm)" }
        ]);

        setTimeout(() => {
          setTerminalHistory(prev => [...prev, 
            { type: "judge", text: "Aegis Goal Judge: NO.\nCritique: The Builder VM successfully initiated but failed to map the external GPU PCI pass-through variables. Ensure Heimdall calls the 'ManagePCI' RPC service to bridge GPU connectivity." },
            { type: "thought", text: "Judge feedback received: GPU passage was omitted. I must execute the 'ManagePCI' tool to configure GPU forwarding." },
            { type: "tool", text: "Executing qrexec: aegis.ManagePCI (VM: app-immich-vm, PCI: 00:02.0)" }
          ]);

          setTimeout(() => {
            setTerminalHistory(prev => [...prev, 
              { type: "judge", text: "Aegis Goal Judge: YES.\nVerification: Salt state successfully completed. External GPU pass-through mapped to app-immich-vm. Immich AI node is responsive on 10.137.0.5." },
              { type: "final", text: "Goal Achieved after 2 iterations!\n\nSuccessfully set up Immich in app-immich-vm with external GPU connectivity and networking bridged through sys-vpn." }
            ]);
            setIsProcessing(false);
          }, 1500);
        }, 1500);

      } else if (query.toLowerCase().includes("monero") || query.toLowerCase().includes("daemon")) {
        setTerminalHistory(prev => [...prev, 
          { type: "thought", text: "User wants to route a daemon as a hidden service. I must consult our skills and deploy a Darknet Scout to map routing paths." },
          { type: "tool", text: "Executing tool: deploy_darknet_scout (protocol: Tor, local_port: 18081, onion_port: 80)" }
        ]);

        setTimeout(() => {
          setTerminalHistory(prev => [...prev, 
            { type: "thought", text: "Darknet Scout deployed successfully. The service is accessible at: aegismonero3z...onion. I will configure WireGuard private gateway rules to allow local access as well." },
            { type: "final", text: "Success: Monero daemon is now dual-accessible:\n\n1. Privately via WireGuard VPN at http://10.137.0.1:18081\n2. Globally as a secure Onion Service at http://aegismonero3z7qgfy3p4shgpfqpy7df359hfg.onion\n\nAll routing paths are secured." }
          ]);
          setIsProcessing(false);
        }, 2000);

      } else {
        // Standard chat query
        setTerminalHistory(prev => [...prev, 
          { type: "thought", text: "Consulting offline knowledge base for current security posture..." },
          { type: "tool", text: "Calling RPC: aegis.GetContext (query: 'security state')" }
        ]);

        setTimeout(() => {
          setTerminalHistory(prev => [...prev, 
            { type: "thought", text: "Context retrieved. Packages verified. Resolving query response." },
            { type: "final", text: "Current Security Posture:\n- sys-copilot: Offline loop active.\n- sys-vpn (Connected): Packages match checksum verifications. WireGuard handshake validated with peer.\n- All RPC interfaces matching 30-aegis.policy validation rules." }
          ]);
          setIsProcessing(false);
        }, 1200);
      }
    }, 1000);
  };

  const copyPairingKey = () => {
    navigator.clipboard.writeText("eS1Mdm01VkdSTUdyZWN...[AEGIS-SECURITY-PAIRING-TOKEN]...83Y=");
    setCopiedKey(true);
    setTimeout(() => setCopiedKey(false), 2000);
  };

  return (
    <div className="flex flex-col min-h-screen bg-slate-950 text-slate-100">
      {/* Top Banner */}
      <header className="border-b border-slate-800 bg-slate-900/60 backdrop-blur px-6 py-4 flex flex-col md:flex-row justify-between items-center gap-4">
        <div className="flex items-center gap-3">
          <div className="p-2 bg-emerald-950/80 border border-emerald-500/50 rounded-lg text-emerald-400 animate-pulse">
            <Shield className="w-6 h-6" />
          </div>
          <div>
            <h1 className="text-xl font-bold tracking-tight text-white flex items-center gap-2">
              Qubes Aegis <span className="text-xs bg-emerald-500/10 text-emerald-400 px-2 py-0.5 border border-emerald-500/20 rounded-full">AI Fork</span>
            </h1>
            <p className="text-xs text-slate-400">Compartmentalized Intelligence & Secure Gateway Dashboard</p>
          </div>
        </div>
        
        {/* Active Node Status Pill */}
        <div className="flex flex-wrap items-center gap-3 bg-slate-950/80 p-1.5 px-3 border border-slate-800 rounded-full text-xs">
          <span className="text-slate-400">System Status:</span>
          <div className="flex items-center gap-1 text-emerald-400">
            <span className="w-2 h-2 rounded-full bg-emerald-400"></span>
            <span>sys-vpn: Active</span>
          </div>
          <span className="text-slate-700">|</span>
          <div className="flex items-center gap-1 text-purple-400">
            <span className="w-2 h-2 rounded-full bg-purple-400"></span>
            <span>sys-copilot: Active</span>
          </div>
          <span className="text-slate-700">|</span>
          <div className="flex items-center gap-1 text-blue-400">
            <span className="w-2 h-2 rounded-full bg-blue-400"></span>
            <span>sys-ai: Ready</span>
          </div>
        </div>
      </header>

      {/* Main Container Layout */}
      <div className="flex flex-1 flex-col md:flex-row">
        {/* Navigation Rail */}
        <nav className="w-full md:w-64 border-r border-slate-800 bg-slate-900/20 flex flex-row md:flex-col p-4 gap-2 overflow-x-auto md:overflow-x-visible shrink-0">
          <button 
            onClick={() => setActiveTab("topology")}
            className={`flex items-center gap-3 px-4 py-3 rounded-lg text-sm font-medium transition-all ${
              activeTab === "topology" 
                ? "bg-emerald-950/50 text-emerald-300 border border-emerald-800/60" 
                : "text-slate-400 hover:text-slate-100 hover:bg-slate-900"
            }`}
          >
            <Network className="w-4 h-4" />
            <span className="whitespace-nowrap">Qubes VM Map</span>
          </button>
          
          <button 
            onClick={() => setActiveTab("mobile")}
            className={`flex items-center gap-3 px-4 py-3 rounded-lg text-sm font-medium transition-all ${
              activeTab === "mobile" 
                ? "bg-emerald-950/50 text-emerald-300 border border-emerald-800/60" 
                : "text-slate-400 hover:text-slate-100 hover:bg-slate-900"
            }`}
          >
            <Smartphone className="w-4 h-4" />
            <span className="whitespace-nowrap">Aegis Tunnel (Mobile)</span>
          </button>
          
          <button 
            onClick={() => setActiveTab("terminal")}
            className={`flex items-center gap-3 px-4 py-3 rounded-lg text-sm font-medium transition-all ${
              activeTab === "terminal" 
                ? "bg-purple-950/50 text-purple-300 border border-purple-800/60" 
                : "text-slate-400 hover:text-slate-100 hover:bg-slate-900"
            }`}
          >
            <Terminal className="w-4 h-4" />
            <span className="whitespace-nowrap">Heimdall CLI / Goals</span>
          </button>
          
          <button 
            onClick={() => setActiveTab("devlog")}
            className={`flex items-center gap-3 px-4 py-3 rounded-lg text-sm font-medium transition-all ${
              activeTab === "devlog" 
                ? "bg-slate-800 text-slate-100 border border-slate-700" 
                : "text-slate-400 hover:text-slate-100 hover:bg-slate-900"
            }`}
          >
            <BookOpen className="w-4 h-4" />
            <span className="whitespace-nowrap">Development Log</span>
          </button>

          <div className="hidden md:block mt-auto p-4 border border-slate-800 bg-slate-900/40 rounded-xl text-[11px] text-slate-400 leading-relaxed">
            <div className="flex items-center gap-2 mb-2 font-semibold text-slate-300">
              <Lock className="w-3.5 h-3.5 text-emerald-400" />
              <span>Security Isolation Rules</span>
            </div>
            <span>No administrative functions are exposed to the network. Connection via <strong className="text-slate-200">sys-vpn</strong> requires authenticated cryptographic WireGuard certificates and passes sanitizing qrexec validations in <strong className="text-slate-200">sys-copilot</strong>.</span>
          </div>
        </nav>

        {/* Content Box */}
        <main className="flex-1 p-6 overflow-y-auto">
          {activeTab === "topology" && (
            <div className="space-y-6">
              <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
                
                {/* Visual Architecture Map */}
                <div className="lg:col-span-2 border border-slate-800 bg-slate-900/40 rounded-2xl p-6 relative overflow-hidden flex flex-col min-h-[420px]">
                  <div className="absolute top-0 right-0 p-8 text-slate-800/30 font-mono text-8xl font-bold -z-10 select-none">
                    AEGIS
                  </div>
                  
                  <div className="flex justify-between items-center mb-6">
                    <div>
                      <h3 className="text-base font-semibold text-white">Qubes Virtualization & Routing Topology</h3>
                      <p className="text-xs text-slate-400">Click on any VM block to inspect packaged configurations and RPC rules.</p>
                    </div>
                    <div className="flex items-center gap-2 bg-slate-900 p-1 px-2 rounded text-[11px] font-mono border border-slate-800 text-slate-400">
                      <Radio className="w-3.5 h-3.5 text-emerald-400 animate-pulse" />
                      <span>Live Flow Mapping</span>
                    </div>
                  </div>

                  {/* Topology Diagram Container */}
                  <div className="flex-1 grid grid-cols-1 sm:grid-cols-3 gap-4 items-center justify-center p-2">
                    
                    {/* Left Column: Network Bound */}
                    <div className="flex flex-col gap-4">
                      {/* WAN */}
                      <div className="border border-dashed border-slate-700 bg-slate-900/20 p-3 rounded-xl text-center">
                        <span className="text-[10px] font-mono tracking-wider uppercase text-slate-400">External Network (WAN)</span>
                        <div className="text-xs text-slate-300 font-semibold mt-1">Aegis Tunnel App (Phone)</div>
                        <div className="text-[10px] text-slate-500 font-mono mt-0.5">10.137.0.2</div>
                      </div>
                      
                      <div className="flex justify-center text-slate-600">
                        <ArrowRight className="rotate-90 sm:rotate-0 w-5 h-5 animate-pulse" />
                      </div>

                      {/* sys-vpn */}
                      <button 
                        onClick={() => setSelectedNode(VM_NODES[2])}
                        className={`p-4 border rounded-xl text-left transition-all relative ${
                          selectedNode.id === "sys-vpn" 
                            ? "border-emerald-500 bg-emerald-950/20" 
                            : "border-slate-800 bg-slate-900/60 hover:border-slate-700"
                        }`}
                      >
                        <div className="flex justify-between items-center mb-1">
                          <span className="text-[10px] font-mono text-emerald-400">sys-vpn (NetVM)</span>
                          <span className="w-2 h-2 rounded-full bg-emerald-400 animate-ping"></span>
                        </div>
                        <div className="font-semibold text-xs text-white">Gateway Peer</div>
                        <div className="text-[10px] text-slate-500 font-mono mt-1">WireGuard Server (Port 51820)</div>
                      </button>
                    </div>

                    {/* Middle Column: Bridges */}
                    <div className="flex flex-col justify-center items-center gap-2">
                      <div className="text-[10px] font-mono text-purple-400 uppercase tracking-widest text-center px-2 py-0.5 bg-purple-950/50 border border-purple-900/40 rounded">
                        Qrexec Tunnel (aegis.HeimdallChat)
                      </div>
                      <div className="w-full flex justify-center text-purple-500">
                        <ArrowRight className="rotate-90 sm:rotate-0 w-8 h-8 animate-pulse" />
                      </div>
                    </div>

                    {/* Right Column: Offline Security Core */}
                    <div className="flex flex-col gap-4">
                      {/* sys-copilot */}
                      <button 
                        onClick={() => setSelectedNode(VM_NODES[1])}
                        className={`p-4 border rounded-xl text-left transition-all ${
                          selectedNode.id === "sys-copilot" 
                            ? "border-purple-500 bg-purple-950/20" 
                            : "border-slate-800 bg-slate-900/60 hover:border-slate-700"
                        }`}
                      >
                        <div className="flex justify-between items-center mb-1">
                          <span className="text-[10px] font-mono text-purple-400">sys-copilot</span>
                          <span className="text-[10px] px-1.5 py-0.5 rounded bg-slate-800 border border-slate-700 font-mono text-slate-400">Offline</span>
                        </div>
                        <div className="font-semibold text-xs text-white">Heimdall Agent Daemon</div>
                        <div className="text-[10px] text-slate-500 font-mono mt-1">/run/aegis/heimdall.sock</div>
                      </button>

                      {/* sys-ai */}
                      <button 
                        onClick={() => setSelectedNode(VM_NODES[3])}
                        className={`p-4 border rounded-xl text-left transition-all ${
                          selectedNode.id === "sys-ai" 
                            ? "border-blue-500 bg-blue-950/20" 
                            : "border-slate-800 bg-slate-900/60 hover:border-slate-700"
                        }`}
                      >
                        <div className="text-[10px] font-mono text-blue-400 mb-1">sys-ai</div>
                        <div className="font-semibold text-xs text-white">Model Execution VM</div>
                        <div className="text-[10px] text-slate-500 font-mono mt-1">Llama Server Sandbox</div>
                      </button>
                    </div>

                  </div>
                </div>

                {/* Node Detail Inspector */}
                <div className="border border-slate-800 bg-slate-900/40 rounded-2xl p-6 flex flex-col justify-between">
                  <div>
                    <div className="flex items-center gap-2 mb-4">
                      <Cpu className="w-5 h-5 text-emerald-400" />
                      <h3 className="text-base font-semibold text-white">Component Inspector</h3>
                    </div>
                    
                    {/* Node Summary Card */}
                    <div className={`p-4 border rounded-xl mb-4 ${selectedNode.color}`}>
                      <h4 className="font-semibold text-sm text-white mb-1">{selectedNode.name}</h4>
                      <p className="text-[11px] uppercase tracking-wider font-mono opacity-80">{selectedNode.type}</p>
                      <div className="flex items-center gap-1.5 mt-2 text-xs">
                        <span className="w-2.5 h-2.5 rounded-full bg-current"></span>
                        <span>State: {selectedNode.status}</span>
                      </div>
                    </div>

                    <p className="text-xs text-slate-300 leading-relaxed mb-6">
                      {selectedNode.description}
                    </p>

                    {/* RPM Files */}
                    <div className="space-y-2 mb-6">
                      <div className="text-xs font-semibold text-slate-400 uppercase tracking-wider">Packaged Artifacts</div>
                      <div className="space-y-1">
                        {selectedNode.rpms.map((rpm, i) => (
                          <div key={i} className="flex items-center gap-2 bg-slate-950 p-2 rounded border border-slate-900 text-[11px] font-mono text-slate-300">
                            <Layers className="w-3.5 h-3.5 text-slate-500" />
                            <span>{rpm}.rpm</span>
                          </div>
                        ))}
                      </div>
                    </div>

                    {/* Salt and Rules */}
                    <div className="space-y-2">
                      <div className="text-xs font-semibold text-slate-400 uppercase tracking-wider">SaltStack / Policy Mapping</div>
                      <div className="bg-slate-950 p-3 rounded-lg border border-slate-900 font-mono text-[10px] text-slate-300 space-y-1">
                        <div className="flex justify-between">
                          <span className="text-slate-500">Formula/Spec File:</span>
                          <span className="text-slate-300 font-semibold">{selectedNode.salt}</span>
                        </div>
                        <div className="flex justify-between">
                          <span className="text-slate-500">Default Target:</span>
                          <span className="text-slate-300">Dom0 Integration</span>
                        </div>
                      </div>
                    </div>
                  </div>

                  <div className="mt-6 pt-4 border-t border-slate-800 text-[10px] text-slate-500 flex items-center gap-2 font-mono">
                    <CheckCircle className="w-3.5 h-3.5 text-emerald-500" />
                    <span>Cryptographic verifications active</span>
                  </div>
                </div>

              </div>

              {/* RPC Policy Inspection Segment */}
              <div className="border border-slate-800 bg-slate-900/20 rounded-2xl p-6">
                <div className="flex justify-between items-center mb-4">
                  <div className="flex items-center gap-2">
                    <Lock className="w-5 h-5 text-purple-400" />
                    <div>
                      <h4 className="text-sm font-semibold text-white">Active Qrexec Policy Rules (30-aegis.policy)</h4>
                      <p className="text-xs text-slate-400">Strict inter-VM permission definitions located in Dom0.</p>
                    </div>
                  </div>
                  <span className="text-xs bg-slate-800 border border-slate-700 px-2.5 py-1 rounded text-slate-300 font-mono">/etc/qubes-rpc.policy/30-aegis.policy</span>
                </div>

                <div className="bg-slate-950 p-4 rounded-xl border border-slate-900 font-mono text-xs text-slate-300 space-y-2 max-h-[180px] overflow-y-auto">
                  <div className="text-slate-500"># Guest AppVMs → sys-copilot: Context queries</div>
                  <div className="flex justify-between py-0.5 hover:bg-slate-900 px-1 rounded">
                    <span>aegis.GetContext</span>
                    <span className="text-slate-500">*</span>
                    <span className="text-purple-400">@tag:aegis-guest</span>
                    <span className="text-blue-400">sys-copilot</span>
                    <span className="text-emerald-400 font-semibold">allow</span>
                  </div>
                  
                  <div className="text-slate-500 mt-2"># Guest AppVMs → sys-copilot: Heimdall chat or goal queries</div>
                  <div className="flex justify-between py-0.5 hover:bg-slate-900 px-1 rounded">
                    <span className="text-white">aegis.HeimdallChat</span>
                    <span className="text-slate-500">*</span>
                    <span className="text-purple-400">@tag:aegis-guest</span>
                    <span className="text-blue-400">sys-copilot</span>
                    <span className="text-emerald-400 font-semibold">allow</span>
                  </div>

                  <div className="text-slate-500 mt-2"># sys-copilot → sys-ai: inference forwarding</div>
                  <div className="flex justify-between py-0.5 hover:bg-slate-900 px-1 rounded">
                    <span>aegis.LLMProxy</span>
                    <span className="text-slate-500">*</span>
                    <span className="text-blue-400">sys-copilot</span>
                    <span className="text-blue-400">sys-ai</span>
                    <span className="text-emerald-400 font-semibold">allow</span>
                  </div>

                  <div className="text-slate-500 mt-2"># Default catch-all denies</div>
                  <div className="flex justify-between py-0.5 hover:bg-slate-900 px-1 rounded">
                    <span>aegis.*</span>
                    <span className="text-slate-500">*</span>
                    <span className="text-slate-500">@anyvm</span>
                    <span className="text-slate-500">@anyvm</span>
                    <span className="text-red-400 font-semibold">deny</span>
                  </div>
                </div>
              </div>
            </div>
          )}

          {activeTab === "mobile" && (
            <div className="space-y-6">
              <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
                
                {/* QR Code and Pairing parameters */}
                <div className="border border-slate-800 bg-slate-900/40 rounded-2xl p-6 flex flex-col justify-between">
                  <div className="space-y-6">
                    <div>
                      <h3 className="text-base font-semibold text-white">Mobile Device Pairing Center</h3>
                      <p className="text-xs text-slate-400">Scan this cryptographically signed code inside the Aegis Tunnel App to join.</p>
                    </div>

                    {/* Vector Pixel QR Code */}
                    <div className="flex justify-center p-4 bg-white rounded-xl max-w-[220px] mx-auto shadow-2xl">
                      <svg width="180" height="180" viewBox="0 0 29 29" className="text-slate-900 fill-current">
                        <path d="M0,0h7v7h-7z M2,2v3h3v-3z M22,0h7v7h-7z M24,2v3h3v-3z M0,22h7v7h-7z M2,24v3h3v-3z" />
                        <path d="M9,0h2v1H9z M13,0h5v2h-5z M19,0h2v1H19z M10,3h1v1h-1z M14,3h2v1h-2z M18,3h1v1h-1z" />
                        <path d="M9,5h4v1H9z M15,5h2v1H15z M18,5h3v2h-3z M9,8h1v3H9z M12,8h3v1h-3z" />
                        <path d="M16,8h2v2h-2z M20,8h4v1H20z M26,8h3v1H26z M10,12h2v1H10z M14,12h3v1h-3z" />
                        <path d="M19,11h2v3H19z M23,11h3v1H23z M27,11h2v1H27z M9,15h3v2H9z M13,15h1v1H13z" />
                        <path d="M16,15h3v1H16z M21,15h4v1H21z M27,15h2v1H27z M9,19h2v1H9z M13,19h3v1H13z" />
                        <path d="M18,18h2v1H18z M22,18h3v2H22z M27,18h2v1H27z M9,22h2v1H9z M13,22h3v1H13z" />
                        <path d="M18,21h2v1H18z M22,21h3v2H22z M27,21h2v1H27z" />
                      </svg>
                    </div>

                    {/* Parameters summary */}
                    <div className="space-y-2">
                      <div className="flex justify-between text-xs border-b border-slate-800 pb-2">
                        <span className="text-slate-400">Endpoint Target</span>
                        <span className="text-white font-mono font-medium">sys-vpn (WireGuard)</span>
                      </div>
                      <div className="flex justify-between text-xs border-b border-slate-800 pb-2">
                        <span className="text-slate-400">VPN Protocol</span>
                        <span className="text-emerald-400 font-mono font-medium">WireGuard UDP:51820</span>
                      </div>
                      <div className="flex justify-between text-xs border-b border-slate-800 pb-2">
                        <span className="text-slate-400">Vault Sync Protocol</span>
                        <span className="text-cyan-400 font-mono font-medium">Syncthing SSL:22000</span>
                      </div>
                    </div>
                  </div>

                  <button 
                    onClick={triggerSimulatedHandshake}
                    className={`mt-6 w-full py-2.5 rounded-xl text-xs font-semibold flex items-center justify-center gap-2 transition-all ${
                      isConnected 
                        ? "bg-red-950/40 border border-red-800/60 text-red-300 hover:bg-red-900/20" 
                        : "bg-emerald-500 text-slate-950 hover:bg-emerald-400"
                    }`}
                  >
                    <RefreshCw className={`w-4 h-4 ${isConnected ? "animate-spin" : ""}`} />
                    <span>{isConnected ? "Disconnect Mobile Device" : "Simulate Phone Handshake"}</span>
                  </button>
                </div>

                {/* Handshake Logs terminal */}
                <div className="lg:col-span-2 border border-slate-800 bg-slate-900/40 rounded-2xl p-6 flex flex-col justify-between min-h-[400px]">
                  <div className="flex flex-col flex-1">
                    <div className="flex justify-between items-center mb-4">
                      <div className="flex items-center gap-2">
                        <Activity className="w-5 h-5 text-emerald-400" />
                        <h3 className="text-base font-semibold text-white">Live sys-vpn Handshake & Syncthing Logs</h3>
                      </div>
                      <span className={`px-2 py-0.5 rounded text-[10px] font-mono border ${
                        isConnected ? "bg-emerald-950/50 text-emerald-400 border-emerald-800/50" : "bg-slate-900 text-slate-500 border-slate-800"
                      }`}>
                        {isConnected ? "CONNECTION: ACTIVE" : "TUNNEL IDLE"}
                      </span>
                    </div>

                    {/* Log Lines */}
                    <div className="flex-1 bg-slate-950 p-4 rounded-xl border border-slate-900 font-mono text-xs text-slate-300 overflow-y-auto space-y-2 max-h-[300px]">
                      {handshakeLogs.map((log, index) => (
                        <div key={index} className="flex gap-2 leading-relaxed">
                          <span className="text-slate-600 select-none">[{index + 1}]</span>
                          <span className={log.includes("Handshake") || log.includes("established") || log.includes("ACTIVE") ? "text-emerald-400 font-medium" : "text-slate-300"}>
                            {log}
                          </span>
                        </div>
                      ))}
                    </div>
                  </div>

                  {/* Manual Pairing Token */}
                  <div className="mt-6 pt-4 border-t border-slate-800 space-y-2">
                    <div className="text-xs font-semibold text-slate-400 uppercase tracking-wider">Manual Pairing Token</div>
                    <div className="flex gap-2">
                      <div className="flex-1 bg-slate-950 border border-slate-900 p-2.5 rounded-lg text-[11px] font-mono text-slate-400 select-all truncate">
                        eS1Mdm01VkdSTUdyZWN...[AEGIS-SECURITY-PAIRING-TOKEN]...83Y=
                      </div>
                      <button 
                        onClick={copyPairingKey}
                        className="bg-slate-800 hover:bg-slate-700 text-slate-200 p-2.5 rounded-lg transition-all text-xs flex items-center gap-1 shrink-0"
                      >
                        {copiedKey ? <Check className="w-4 h-4 text-emerald-400" /> : <Copy className="w-4 h-4" />}
                        <span>{copiedKey ? "Copied" : "Copy"}</span>
                      </button>
                    </div>
                  </div>
                </div>

              </div>
            </div>
          )}

          {activeTab === "terminal" && (
            <div className="space-y-6">
              
              {/* Interactive CLI Console */}
              <div className="border border-slate-800 bg-slate-900/40 rounded-2xl p-6 flex flex-col min-h-[460px]">
                <div className="flex justify-between items-center mb-4">
                  <div className="flex items-center gap-2">
                    <Terminal className="w-5 h-5 text-purple-400" />
                    <div>
                      <h3 className="text-base font-semibold text-white">Heimdall Agent Terminal (sys-copilot)</h3>
                      <p className="text-xs text-slate-400">Secure execution terminal running in isolated sys-copilot.</p>
                    </div>
                  </div>
                  <span className="text-xs bg-slate-800 border border-slate-700 px-2.5 py-1 rounded text-slate-300 font-mono">/run/aegis/heimdall.sock</span>
                </div>

                {/* Pre-canned prompt suggestions */}
                <div className="flex flex-wrap gap-2 mb-4">
                  {PRE_CANNED_QUERIES.map((item, idx) => (
                    <button 
                      key={idx}
                      onClick={() => handleTerminalSubmit(item.query)}
                      disabled={isProcessing}
                      className="bg-slate-900 hover:bg-slate-800 disabled:opacity-50 text-[11px] border border-slate-800 hover:border-slate-700 text-slate-300 px-3 py-1.5 rounded-full transition-all flex items-center gap-1"
                    >
                      <span>{item.label}</span>
                      <ChevronRight className="w-3 h-3" />
                    </button>
                  ))}
                </div>

                {/* Terminal Screens */}
                <div className="flex-1 bg-slate-950 p-4 rounded-xl border border-slate-900 font-mono text-xs overflow-y-auto space-y-4 min-h-[220px] max-h-[280px]">
                  {terminalHistory.map((item, i) => (
                    <div key={i} className="space-y-1">
                      {item.type === "user" && (
                        <div className="text-white flex items-center gap-1.5 font-semibold">
                          <span className="text-emerald-500">guest@aegis-node:~$</span>
                          <span>{item.text}</span>
                        </div>
                      )}
                      {item.type === "thought" && (
                        <div className="text-purple-400 bg-purple-950/20 p-2 border-l-2 border-purple-500 rounded-r-lg">
                          <span className="font-semibold text-[10px] block uppercase tracking-wider mb-0.5">Thought (Sense-Think-Act Loop)</span>
                          <span className="leading-relaxed">{item.text}</span>
                        </div>
                      )}
                      {item.type === "tool" && (
                        <div className="text-cyan-300 bg-cyan-950/20 p-2 border-l-2 border-cyan-500 rounded-r-lg flex items-center gap-2">
                          <Activity className="w-3.5 h-3.5 animate-spin" />
                          <span>{item.text}</span>
                        </div>
                      )}
                      {item.type === "judge" && (
                        <div className="text-yellow-400 bg-yellow-950/20 p-2 border-l-2 border-yellow-500 rounded-r-lg">
                          <span className="font-semibold text-[10px] block uppercase tracking-wider mb-0.5">Aegis Goal Judge Reflection</span>
                          <span className="leading-relaxed whitespace-pre-wrap">{item.text}</span>
                        </div>
                      )}
                      {item.type === "final" && (
                        <div className="text-slate-300 leading-relaxed whitespace-pre-wrap pl-4 border-l border-slate-800">
                          {item.text}
                        </div>
                      )}
                      {item.type === "error" && (
                        <div className="text-red-400 flex items-center gap-1.5 pl-4">
                          <AlertTriangle className="w-3.5 h-3.5" />
                          <span>{item.text}</span>
                        </div>
                      )}
                    </div>
                  ))}
                  {isProcessing && (
                    <div className="flex items-center gap-2 text-slate-500 text-[11px] animate-pulse">
                      <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                      <span>Heimdall is thinking...</span>
                    </div>
                  )}
                  <div ref={terminalEndRef} />
                </div>

                {/* Input row */}
                <form 
                  onSubmit={(e) => {
                    e.preventDefault();
                    handleTerminalSubmit();
                  }}
                  className="mt-4 flex gap-2"
                >
                  <input 
                    type="text"
                    value={terminalInput}
                    onChange={(e) => setTerminalInput(e.target.value)}
                    disabled={isProcessing}
                    placeholder="Ask Heimdall or set up an absolute goal (e.g. /goal set up VPN)..."
                    className="flex-1 bg-slate-950 border border-slate-800 focus:border-purple-500 rounded-xl px-4 py-2.5 text-xs text-white font-mono placeholder:text-slate-600 outline-none"
                  />
                  <button 
                    type="submit"
                    disabled={isProcessing || !terminalInput.trim()}
                    className="bg-purple-600 hover:bg-purple-500 disabled:opacity-50 text-white p-2.5 px-4 rounded-xl transition-all flex items-center gap-1.5 text-xs font-semibold"
                  >
                    <Send className="w-3.5 h-3.5" />
                    <span>Send</span>
                  </button>
                </form>
              </div>

              {/* Goal loop diagram */}
              <div className="border border-slate-800 bg-slate-900/20 rounded-2xl p-6">
                <h4 className="text-sm font-semibold text-white mb-2">Goal Execution Architecture (Builder & Judge)</h4>
                <p className="text-xs text-slate-400 mb-4">When a goal MUST be finished, the system loops autonomously using dual-LLM confirmation before reporting back.</p>
                
                <div className="grid grid-cols-1 md:grid-cols-4 gap-4 items-center">
                  <div className="bg-slate-950 p-3 rounded-xl border border-slate-900 text-center text-xs">
                    <div className="font-semibold text-white mb-1">1. Set Goal</div>
                    <span className="text-[10px] text-slate-500">User defines an absolute criteria (/goal)</span>
                  </div>
                  <div className="text-center text-slate-600">→</div>
                  <div className="bg-slate-950 p-3 rounded-xl border border-slate-900 text-center text-xs">
                    <div className="font-semibold text-purple-400 mb-1">2. Sense-Think-Act</div>
                    <span className="text-[10px] text-slate-500">Heimdall Builder attempts to compile/apply config</span>
                  </div>
                  <div className="text-center text-slate-600">→</div>
                  <div className="bg-slate-950 p-3 rounded-xl border border-slate-900 text-center text-xs">
                    <div className="font-semibold text-yellow-400 mb-1">3. Evaluate (Judge)</div>
                    <span className="text-[10px] text-slate-500">Judge LLM verifies all criteria are met</span>
                  </div>
                  <div className="text-center text-slate-600">→</div>
                  <div className="bg-slate-950 p-3 rounded-xl border border-slate-900 text-center text-xs">
                    <div className="font-semibold text-emerald-400 mb-1">4. Complete/Repeat</div>
                    <span className="text-[10px] text-slate-500">Completed (YES) or looped back with feedback (NO)</span>
                  </div>
                </div>
              </div>

            </div>
          )}

          {activeTab === "devlog" && (
            <div className="border border-slate-800 bg-slate-900/40 rounded-2xl p-6 space-y-4 max-w-4xl mx-auto">
              <div className="flex justify-between items-center border-b border-slate-800 pb-4">
                <div className="flex items-center gap-2">
                  <FileText className="w-5 h-5 text-slate-400" />
                  <h3 className="text-base font-semibold text-white">System Development Log (DEVLOG.md)</h3>
                </div>
                <span className="text-xs text-slate-500 font-mono">Synced on 2026-07-02</span>
              </div>
              
              <div className="prose prose-invert prose-xs max-w-none font-mono text-xs leading-relaxed text-slate-300 space-y-6 select-text">
                <pre className="whitespace-pre-wrap bg-slate-950 p-4 rounded-xl border border-slate-900 max-h-[480px] overflow-y-auto">
                  {DEVLOG_CONTENT}
                </pre>
              </div>
            </div>
          )}
        </main>
      </div>

      {/* Footer bar */}
      <footer className="border-t border-slate-800 bg-slate-950 px-6 py-4 flex flex-col md:flex-row justify-between items-center text-slate-500 text-xs gap-4 font-mono">
        <span>Aegis Cryptographic Administration Environment v1.0.0</span>
        <span>Securely bound on Port 3000 (HTTPS reverse proxy active)</span>
      </footer>
    </div>
  );
}
