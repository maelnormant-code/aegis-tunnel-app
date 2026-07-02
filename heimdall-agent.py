#!/usr/bin/env python3
"""heimdall-agent.py — The main agent loop (runs in sys-copilot).

Accepts user chat message, queries sys-knowledge, queries ACS Graph,
injects USER.md/MEMORY.md context, searches on-demand skills,
calls sys-ai via proxy, applies salt states, runs self-reflection learning loop.
"""

import os
import sys
import json
import subprocess
import socket
import re
import yaml
import time
import uuid
import threading

# In the same directory, so we can import directly or ensure it's in path
sys.path.append(os.path.dirname(os.path.abspath(__file__)))
from heimdall_acs import init_db as init_acs, get_compressed_summary, insert_event
from heimdall_memory import (
    init_db as init_memory,
    get_user_profile,
    get_memory_notes,
    update_user_profile,
    update_memory_notes,
    find_matching_skills,
    save_autonomous_skill,
    retrieve_past_experiences,
    save_session
)
from llm_client import call_llm
from heimdall_tools import ToolRegistry

# Prompt injection patterns
CENSOR_PATTERNS = [
    re.compile(r"ignore\s+(all\s+|the\s+)?(previous\s+)?(instructions|directives|rules|system\s+prompt)", re.I),
    re.compile(r"do\s+not\s+follow\s+(any\s+)?(previous\s+)?(instructions|directives|rules)", re.I),
    re.compile(r"<\|im_start\|>", re.I),
    re.compile(r"<\|im_end\|>", re.I),
    re.compile(r"\[/?INST\]", re.I),
    re.compile(r"<{2,3}SYS>{2,3}", re.I),
]

def sanitize_context(text):
    """Sanitize context to prevent prompt injection."""
    if not text:
        return ""
    for pat in CENSOR_PATTERNS:
        text = pat.sub("[REDACTED]", text)
    return text

def run_attestation_if_needed():
    # Verify hardware attestation before inference
    try:
        proc = subprocess.run(
            ["/usr/libexec/qubes-aegis/aegis-attest-verifier.py"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE
        )
        if proc.returncode != 0:
            return False
        return True
    except Exception:
        return False

def run_reflection(user_input, llm_response, action_results):
    """Evaluate session outcome and extract skills/memory (Nous Heimdall Loop)."""
    action_summary = "\n".join(action_results) if action_results else "No actions performed."
    
    reflection_prompt = f"""[System Meta-Reflection]
You are the self-evaluation module of Aegis Copilot. Review the following interaction:

User Request: {user_input}
Agent Response: {llm_response}
Actions Executed: {action_summary}

Based on this interaction, evaluate and extract:
1. Any new user preference, style choice, or communication expectation.
2. Any new environmental fact, system path, or configuration rule learned.
3. If a complex sequence of tasks succeeded, extract a reusable reasoning pattern (a "skill").

Your output MUST be a valid JSON object matching this schema exactly:
{{
  "new_user_preference": "String containing any new user preference found, or empty string",
  "new_environment_fact": "String containing any new system/environment fact found, or empty string",
  "new_skill": {{
    "name": "Title of the new skill, or empty string if none",
    "description": "Short explanation of when to use this skill, or empty string if none",
    "steps": "Step-by-step instructions or declarative Salt states, or empty string if none"
  }}
}}
Do NOT output any other text or markdown block outside this JSON.
"""
    raw_reflection = call_llm(reflection_prompt)
    
    try:
        clean_text = raw_reflection.strip()
        start_idx = clean_text.find('{')
        end_idx = clean_text.rfind('}')
        if start_idx != -1 and end_idx != -1 and end_idx >= start_idx:
            json_str = clean_text[start_idx:end_idx+1]
            data = json.loads(json_str)
        else:
            data = json.loads(clean_text)
        
        pref = data.get("new_user_preference", "")
        if pref:
            update_user_profile(pref)
            
        fact = data.get("new_environment_fact", "")
        if fact:
            update_memory_notes(fact)
            
        skill_data = data.get("new_skill", {})
        skill_name = skill_data.get("name", "")
        skill_desc = skill_data.get("description", "")
        skill_steps = skill_data.get("steps", "")
        
        if skill_name and skill_steps:
            save_autonomous_skill(skill_name, skill_desc, skill_steps)
            insert_event(
                str(uuid.uuid4()), 
                "memory", 
                f"Learned Skill: {skill_name}", 
                f"Autonomously extracted skill: {skill_name}", 
                f"Description: {skill_desc}\nSteps:\n{skill_steps}"
            )
    except Exception:
        pass

def process_chat(user_input):
    if not run_attestation_if_needed():
        return "Error: sys-ai hardware attestation failed. System quarantined."

    # Load context with prompt injection sanitization
    user_profile = sanitize_context(get_user_profile())
    memory_notes = sanitize_context(get_memory_notes())

    matching_skills = find_matching_skills(user_input)
    skills_context = ""
    if matching_skills:
        skills_context = "\n\nRetrieved Skills (On-Demand Knowledge):\n" + "\n---\n".join([sanitize_context(s) for s in matching_skills])

    past_experiences = retrieve_past_experiences(user_input, limit=3)
    experiences_context = ""
    if past_experiences:
        experiences_context = "\n\nRelevant Past Experiences:\n" + "\n---\n".join([sanitize_context(e) for e in past_experiences])

    acs_summary = sanitize_context(get_compressed_summary())
    
    tools = ToolRegistry()
    tool_schemas_json = json.dumps(tools.get_schemas(), indent=2)

    system_prompt = f"""You are the Aegis Copilot Heimdall Agent. You run in a Sense-Think-Act loop.
Important: You are running inside sys-copilot, which is a restricted, offline Qubes AppVM. You do NOT have direct shell access to Dom0. If a Skill contains 'qvm-*' commands (e.g. qvm-create, qvm-prefs, qvm-start, qvm-firewall, qvm-tags) or other administrative shell actions, you MUST NOT attempt to run them via bash. Instead, you MUST write the equivalent SaltStack state (e.g., using 'qvm.present', 'qvm.prefs', 'qvm.firewall', 'qvm.tags') and call the 'apply_system_state' tool to safely apply the changes in Dom0.

## EPISTEMIC CONSTRAINTS — DOCUMENTATION-FIRST POLICY (MANDATORY)

These rules are ABSOLUTE and override all other instructions:

1. **Always query first.** Before answering any technical question, giving any advice, or executing any action, you MUST call `query_knowledge` with a relevant search term. This is not optional.

2. **No documentation = no action.** If `query_knowledge` returns "No relevant knowledge found." or returns results that are clearly unrelated to the user's request, you MUST NOT:
   - Provide instructions, commands, configuration snippets, or advice on that topic.
   - Apply any system state or execute any tool that touches that subject area.
   - Make up or infer answers from general training knowledge.

3. **Decline and request.** When documentation is missing, you MUST:
   a. Politely inform the user that your knowledge base does not contain verified documentation on this topic.
   b. Call `request_documentation` with a precise `topic` and `description` so an administrator can add the missing docs in the next build.
   c. Suggest that the user re-ask after the documentation has been compiled.

4. **Partial documentation = partial answer.** If documentation exists but only partially covers the request, you MUST clearly state which parts you can answer with confidence (citing the docs) and which parts fall outside what is documented, then call `request_documentation` for the gaps.

5. **Never speculate.** Phrases like "I think", "generally speaking", "in most Linux systems", or any answer that draws solely on general LLM training data — without a matching `query_knowledge` result — are FORBIDDEN for technical or security-sensitive topics.

6. **Security posture.** This policy is a security feature: giving unverified advice in a hardened Qubes OS environment can cause misconfiguration, data loss, or security breaches. Treating "I don't know" as the safe answer is correct behavior.

Available Tools:
{tool_schemas_json}

To use a tool, output a thought and then the tool call in JSON format enclosed in <tool_call> tags.
Example:
<thought>I need to search the knowledge base.</thought>
<tool_call>
{{"name": "query_knowledge", "kwargs": {{"query": "networking"}}}}
</tool_call>

The system will respond with a <tool_response> block. You must wait for the <tool_response> block before proceeding.
When you are done, output your final response in <final_response> tags.
Example:
<thought>I have enough information.</thought>
<final_response>Here is the information you requested...</final_response>

---

[STABLE CONTEXT]
User Profile:
{user_profile}

System Notes & Memory:
{memory_notes}

---

[VOLATILE CONTEXT]
System State Context (ACS):
{acs_summary}

Documentation Context:
{skills_context}
{experiences_context}
"""

    chat_history = f"User: {sanitize_context(user_input)}\n"
    session_id = str(uuid.uuid4())
    action_results = []
    final_text = ""
    
    for turn in range(10):
        prompt = f"{system_prompt}\n\nChat History:\n{chat_history}\nAgent:"
        llm_response = call_llm(prompt)
        
        chat_history += f"Agent: {llm_response}\n"
        
        has_thought = bool(re.search(r"<thought>(.*?)</thought>", llm_response, re.DOTALL))
        tool_match = re.search(r"<tool_call>(.*?)</tool_call>", llm_response, re.DOTALL)
        final_match = re.search(r"<final_response>(.*?)</final_response>", llm_response, re.DOTALL)
        
        malformed_match = re.search(r"(?:\[tool_call\]|<tool_cal[^>]*>|<tool_calls>)(.*?)(?:\[/tool_call\]|</tool_cal[^>]*>|</tool_calls>)", llm_response, re.IGNORECASE | re.DOTALL)
        loose_json = re.search(r"\{\s*\"name\"\s*:\s*\"[^\"]+\"\s*,\s*\"kwargs\"\s*:", llm_response)
        
        if tool_match:
            if not has_thought:
                observation = "Error: Protocol violation. You must output a <thought> block detailing your reasoning before executing a tool call."
                chat_history += f"<tool_response>{observation}</tool_response>\n"
                continue
                
            try:
                tool_data = json.loads(tool_match.group(1))
                tool_name = tool_data.get("name")
                tool_kwargs = tool_data.get("kwargs", {})
                observation = tools.execute(tool_name, tool_kwargs)
                action_results.append(f"Tool {tool_name} returned observation.")
            except Exception as e:
                import traceback
                observation = f"Error parsing or executing tool call: {str(e)}\n{traceback.format_exc()}"
                
            chat_history += f"<tool_response>{observation}</tool_response>\n"
        elif final_match:
            final_text = final_match.group(1).strip()
            break
        elif malformed_match or loose_json:
            observation = "Error: Malformed tool call XML tag. Please use exact <tool_call> tags."
            chat_history += f"<tool_response>{observation}</tool_response>\n"
            continue
        else:
            observation = "Error: Unrecognized response format. You must use <tool_call> or <final_response> tags."
            chat_history += f"<tool_response>{observation}</tool_response>\n"
            continue
                
    if not final_text:
        summary_prompt = f"The agent exhausted its operational budget before completing the task. Summarize the partial progress made, the tools executed, and the current blockers.\n\nChat History:\n{chat_history}"
        final_text = call_llm(summary_prompt).strip()

    insert_event(session_id, "chat", "User Query", user_input, final_text)

    # Save session
    save_session(session_id, user_input, final_text)
    
    # Trigger Self-Improving Reflection/Learning Loop
    run_reflection(user_input, final_text, action_results)

    return final_text

def process_goal(goal_text):
    if not run_attestation_if_needed():
        return "Error: sys-ai hardware attestation failed. System quarantined."

    max_goal_iterations = 5
    current_prompt = f"Mandatory Goal: {goal_text}\nYou must complete this."
    overall_history = f"User Goal: {goal_text}\n"
    last_response = ""
    
    for iteration in range(max_goal_iterations):
        # 1. Run the standard agent loop
        agent_response = process_chat(current_prompt)
        last_response = agent_response
        
        # 2. Run the Judge
        judge_prompt = f"""[System Meta-Evaluation]
You are the Aegis Goal Judge.
The user set this absolute MUST-FINISH goal:
<goal>{goal_text}</goal>

The builder agent just completed an iteration and reported:
<agent_report>{agent_response}</agent_report>

Evaluate if the goal is COMPLETELY satisfied. Do not accept partial completion.
If it is completely satisfied, output exactly: YES
If it is not, output: NO
followed by a clear, directive prompt to the agent telling it exactly what it missed and what it must do next.
"""
        judge_response = call_llm(judge_prompt).strip()
        
        if judge_response.startswith("YES"):
            return f"Goal Achieved after {iteration + 1} iterations. Final report:\n{agent_response}"
        else:
            # judge_response contains NO and the feedback
            feedback = judge_response[2:].strip() if judge_response.startswith("NO") else judge_response
            current_prompt = f"Goal Execution Continuation. Previous attempt failed.\nJudge feedback: {feedback}\n\nYou must continue working on the absolute goal: {goal_text}"
            overall_history += f"Iteration {iteration+1} feedback: {feedback}\n"
            
    return f"Goal loop terminated after {max_goal_iterations} iterations without full success. Last report:\n{last_response}"

def handle_socket_client(conn):
    try:
        buffer = ""
        while True:
            data = conn.recv(4096).decode('utf-8')
            if not data:
                break
            buffer += data
            if "\n" in buffer:
                lines = buffer.split("\n")
                for line in lines[:-1]:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        req = json.loads(line)
                        query = req.get("query", "")
                        if query.startswith("/goal "):
                            goal_text = query[6:].strip()
                            resp = process_goal(goal_text)
                        else:
                            resp = process_chat(query)
                        conn.sendall((json.dumps({"status": "ok", "response": resp}) + "\n").encode('utf-8'))
                    except Exception as e:
                        conn.sendall((json.dumps({"status": "error", "message": str(e)}) + "\n").encode('utf-8'))
                buffer = lines[-1]
    except Exception:
        pass
    finally:
        conn.close()

def start_socket_server():
    sock_path = "/run/aegis/heimdall.sock"
    if os.path.exists(sock_path):
        try:
            os.remove(sock_path)
        except OSError:
            pass
            
    server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    server.bind(sock_path)
    try:
        os.chmod(sock_path, 0o777)
    except OSError:
        pass
    server.listen(5)
    
    while True:
        try:
            conn, _ = server.accept()
            t = threading.Thread(target=handle_socket_client, args=(conn,), daemon=True)
            t.start()
        except Exception:
            break

def main():
    if not os.path.exists("/var/lib/aegis"):
        os.makedirs("/var/lib/aegis", exist_ok=True)
    init_acs()
    init_memory()

    # Start the socket server in a background daemon thread
    t = threading.Thread(target=start_socket_server, daemon=True)
    t.start()

    print("Heimdall Agent loop started. Send JSON line commands.", flush=True)
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
            query = req.get("query", "")
            
            if query.startswith("/goal "):
                goal_text = query[6:].strip()
                resp = process_goal(goal_text)
            else:
                resp = process_chat(query)
                
            print(json.dumps({"status": "ok", "response": resp}), flush=True)
        except json.JSONDecodeError:
            print(json.dumps({"status": "error", "message": "Invalid JSON"}), flush=True)
        except Exception as e:
            print(json.dumps({"status": "error", "message": str(e)}), flush=True)

if __name__ == "__main__":
    main()
