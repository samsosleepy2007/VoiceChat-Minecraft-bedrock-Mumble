use std::env;
use std::net::{Ipv4Addr, Ipv6Addr};

use playit_api_client::api::{
    AccountTunnelOriginCreate, AgentOrigin, AgentTunnelAttr, AgentTunnelConfig,
    ConnectAddress, CreateTunnelAllocationRequest, PlayitNetwork, ReqTunnelsCreateV1,
    TunnelPortDetails, UseAllocRegion,
};
use playit_api_client::PlayitApi;
use serde_json::json;

const API_BASE: &str = "https://api.playit.gg";
const TUNNEL_NAME: &str = "VC Mumble Server";

#[tokio::main]
async fn main() {
    if let Err(error) = run().await {
        eprintln!("{error}");
        std::process::exit(1);
    }
}

async fn run() -> Result<(), String> {
    let mut args = env::args().skip(1);
    let command = args.next().ok_or_else(|| "missing command".to_string())?;
    let secret = env::var("PLAYIT_SECRET_KEY")
        .map_err(|_| "PLAYIT_SECRET_KEY is required".to_string())?;
    let api = PlayitApi::create(API_BASE.to_string(), Some(secret));

    match command.as_str() {
        "ensure-mumble" => {
            let local_port: u16 = args
                .next()
                .ok_or_else(|| "missing local port".to_string())?
                .parse()
                .map_err(|_| "invalid local port".to_string())?;
            ensure_mumble(&api, local_port).await
        }
        "list" => list_tunnels(&api).await,
        other => Err(format!("unknown command: {other}")),
    }
}

async fn ensure_mumble(api: &PlayitApi, local_port: u16) -> Result<(), String> {
    let existing = api
        .v1_tunnels_list()
        .await
        .map_err(|error| format!("list tunnels failed: {error}"))?;

    if let Some(tunnel) = existing
        .tunnels
        .iter()
        .find(|tunnel| tunnel.name.as_deref() == Some(TUNNEL_NAME))
    {
        print_tunnel(tunnel, false);
        return Ok(());
    }

    let created = api
        .v1_tunnels_create(ReqTunnelsCreateV1 {
            ports: TunnelPortDetails::CustomBoth(1),
            origin: AccountTunnelOriginCreate::Agent(AgentOrigin {
                agent_id: None,
                config: AgentTunnelConfig {
                    fields: vec![
                        AgentTunnelAttr {
                            name: "local_ip".to_string(),
                            value: "127.0.0.1".to_string(),
                        },
                        AgentTunnelAttr {
                            name: "local_port".to_string(),
                            value: local_port.to_string(),
                        },
                    ],
                },
            }),
            enabled: true,
            alloc: Some(CreateTunnelAllocationRequest::Region(UseAllocRegion {
                region: PlayitNetwork::Global,
                port: None,
            })),
            name: Some(TUNNEL_NAME.to_string()),
            firewall_id: None,
        })
        .await
        .map_err(|error| format!("create tunnel failed: {error}"))?;

    tokio::time::sleep(std::time::Duration::from_millis(800)).await;

    let refreshed = api
        .v1_tunnels_list()
        .await
        .map_err(|error| format!("refresh tunnels failed: {error}"))?;

    if let Some(tunnel) = refreshed.tunnels.iter().find(|tunnel| tunnel.id == created.id) {
        print_tunnel(tunnel, true);
        return Ok(());
    }

    println!(
        "{}",
        json!({
            "ok": true,
            "created": true,
            "tunnel_id": created.id.to_string(),
            "name": TUNNEL_NAME,
            "endpoint": serde_json::Value::Null,
            "pending": true
        })
    );
    Ok(())
}

async fn list_tunnels(api: &PlayitApi) -> Result<(), String> {
    let tunnels = api
        .v1_tunnels_list()
        .await
        .map_err(|error| format!("list tunnels failed: {error}"))?;

    let rows: Vec<_> = tunnels
        .tunnels
        .iter()
        .map(|tunnel| {
            json!({
                "id": tunnel.id.to_string(),
                "name": tunnel.name,
                "enabled": tunnel.user_enabled,
                "endpoint": tunnel.connect_addresses.iter().find_map(connect_address),
                "offline_reasons": tunnel.offline_reasons
            })
        })
        .collect();

    println!("{}", json!({"ok": true, "tunnels": rows}));
    Ok(())
}

fn print_tunnel(tunnel: &playit_api_client::api::AccountTunnelV1, created: bool) {
    let endpoint = tunnel.connect_addresses.iter().find_map(connect_address);
    println!(
        "{}",
        json!({
            "ok": true,
            "created": created,
            "tunnel_id": tunnel.id.to_string(),
            "name": tunnel.name,
            "enabled": tunnel.user_enabled,
            "endpoint": endpoint,
            "offline_reasons": tunnel.offline_reasons
        })
    );
}

fn connect_address(value: &ConnectAddress) -> Option<String> {
    match value {
        ConnectAddress::Addr4(v) => Some(v.address.to_string()),
        ConnectAddress::Addr6(v) => Some(v.address.to_string()),
        ConnectAddress::Ip4(v) => Some(format_socket_v4(v.address, v.default_port)),
        ConnectAddress::Ip6(v) => Some(format_socket_v6(v.address, v.default_port)),
        ConnectAddress::Auto(v) => Some(v.address.clone()),
        ConnectAddress::Domain(v) => Some(v.address.clone()),
    }
}

fn format_socket_v4(ip: Ipv4Addr, port: u16) -> String {
    format!("{ip}:{port}")
}

fn format_socket_v6(ip: Ipv6Addr, port: u16) -> String {
    format!("[{ip}]:{port}")
}
